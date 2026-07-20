package com.qoobot.qoorag.config;

import com.pgvector.PGvector;
import org.hibernate.HibernateException;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.UserType;

import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Hibernate UserType：在 PGvector 与 Java float[] 之间双向映射。
 * <p>注册方式：在 Entity 字段上使用 @Type(PGvectorUserType.class)。
 */
public class PGvectorUserType implements UserType<PGvector> {

    @Override
    public int getSqlType() {
        return Types.OTHER;
    }

    @Override
    public Class<PGvector> returnedClass() {
        return PGvector.class;
    }

    @Override
    public boolean equals(PGvector x, PGvector y) {
        if (x == y) return true;
        if (x == null || y == null) return false;
        return x.equals(y);
    }

    @Override
    public int hashCode(PGvector x) {
        return x != null ? x.hashCode() : 0;
    }

    @Override
    public PGvector nullSafeGet(ResultSet rs, int position,
                                SharedSessionContractImplementor session, Object owner)
            throws SQLException {
        String value = rs.getString(position);
        if (value == null) return null;
        return new PGvector(value);
    }

    @Override
    public void nullSafeSet(PreparedStatement st, PGvector value, int index,
                            SharedSessionContractImplementor session)
            throws SQLException {
        if (value == null) {
            st.setNull(index, Types.OTHER);
        } else {
            st.setObject(index, value);
        }
    }

    @Override
    public PGvector deepCopy(PGvector value) {
        if (value == null) return null;
        return new PGvector(value.toArray());
    }

    @Override
    public boolean isMutable() {
        return true;
    }

    @Override
    public Serializable disassemble(PGvector value) {
        return (Serializable) deepCopy(value);
    }

    @Override
    public PGvector assemble(Serializable cached, Object owner) {
        return (PGvector) cached;
    }
}
