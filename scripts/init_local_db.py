"""初始化本地 qoorag 数据库（schema + pgvector 扩展）。

读取 sql/init.sql 在本工程自己的 PostgreSQL 上执行——即根目录 docker-compose.yml
的 qoorag-postgres（宿主端口默认 5433，用户/库名 qoorag），不借用机器上其它
工程监听 5432 的 PostgreSQL。仅当目标表不存在时才执行，避免重复执行
CREATE POLICY 等幂等性较差的语句报错。
"""
import os
import sys
import psycopg2
from psycopg2 import sql

# 默认指向本工程 docker-compose 的 qoorag-postgres（宿主 5433），
# 与 .env / .env.example 中的 DB_* 保持一致；可用环境变量覆盖。
HOST = os.environ.get("DB_HOST", "127.0.0.1")
PORT = int(os.environ.get("DB_PORT", "5433"))
USER = os.environ.get("DB_USERNAME", "qoorag")
PASSWORD = os.environ.get("DB_PASSWORD", "qoorag")
DB = "qoorag"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
INIT_SQL = os.path.join(ROOT, "sql", "init.sql")


def log(msg):
    print("[init_local_db] " + msg, flush=True)


def main():
    # 1) 连接默认库，确保 qoorag 库存在
    conn = psycopg2.connect(host=HOST, port=PORT, user=USER, password=PASSWORD,
                            dbname="postgres", connect_timeout=10)
    conn.autocommit = True
    cur = conn.cursor()
    cur.execute("SELECT 1 FROM pg_database WHERE datname = %s", (DB,))
    exists = cur.fetchone()
    if not exists:
        log(f"数据库 {DB} 不存在，创建中...")
        cur.execute(sql.SQL("CREATE DATABASE {}").format(sql.Identifier(DB)))
        log(f"数据库 {DB} 已创建")
    else:
        log(f"数据库 {DB} 已存在，跳过创建")
    cur.close()
    conn.close()

    # 2) 连接 qoorag 库
    conn = psycopg2.connect(host=HOST, port=PORT, user=USER, password=PASSWORD,
                            dbname=DB, connect_timeout=10)
    conn.autocommit = True
    cur = conn.cursor()

    # 3) 检查 pgvector 扩展是否可用
    cur.execute("SELECT 1 FROM pg_available_extensions WHERE name = 'vector'")
    if not cur.fetchone():
        log("错误：本地 PostgreSQL 未安装 pgvector 扩展（contrib）。请先安装 pgvector 后重试。")
        sys.exit(2)
    log("pgvector 扩展可用")

    # 4) 若关键表已存在则跳过整段初始化
    cur.execute("SELECT 1 FROM information_schema.tables WHERE table_name = 'tenant'")
    if cur.fetchone():
        log("检测到已存在表（tenant），跳过 init.sql 执行")
        cur.close()
        conn.close()
        return

    log(f"执行 {INIT_SQL} ...")
    with open(INIT_SQL, "r", encoding="utf-8") as f:
        script = f.read()
    try:
        cur.execute(script)
        log("init.sql 执行完成")
    except Exception as e:
        log(f"执行 init.sql 出错: {e}")
        sys.exit(3)
    finally:
        cur.close()
        conn.close()


if __name__ == "__main__":
    main()
