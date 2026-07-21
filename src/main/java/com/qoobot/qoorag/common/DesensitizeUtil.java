package com.qoobot.qoorag.common;

import java.util.regex.Pattern;

/**
 * PII 脱敏工具（#18 等保/数据安全）。
 * 对姓名 / 手机号 / 身份证做掩码展示，不加密（决策：不需要字段级加密）。
 * 适用于审计导出、日志等可能包含个人信息的输出边界。
 */
public final class DesensitizeUtil {

    /** 中国大陆手机号：保留前 3 后 4，中间 **** */
    private static final Pattern PHONE = Pattern.compile("(1[3-9]\\d)(\\d{4})(\\d{4})");
    /** 18 位身份证：保留前 6 后 4 */
    private static final Pattern ID_CARD_18 = Pattern.compile("(\\d{6})(\\d{8})([\\dXx]{4})");
    /** 15 位身份证：保留前 6 后 3 */
    private static final Pattern ID_CARD_15 = Pattern.compile("(\\d{6})(\\d{6})(\\d{3})");

    private DesensitizeUtil() {}

    /** 姓名脱敏：保留首字符，其余用 * 替换（中英文通用）。空值原样返回。 */
    public static String maskName(String name) {
        if (name == null || name.length() <= 1) {
            return name;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(name.charAt(0));
        for (int i = 1; i < name.length(); i++) {
            sb.append('*');
        }
        return sb.toString();
    }

    /** 手机号脱敏：保留前 3 后 4，中间 ****。 */
    public static String maskPhone(String phone) {
        if (phone == null) {
            return null;
        }
        return PHONE.matcher(phone).replaceAll("$1****$3");
    }

    /** 身份证脱敏：18 位保留前 6 后 4；15 位保留前 6 后 3；其余原样。 */
    public static String maskIdCard(String id) {
        if (id == null) {
            return null;
        }
        String s = ID_CARD_18.matcher(id).replaceAll("$1********$3");
        return ID_CARD_15.matcher(s).replaceAll("$1******$3");
    }

    /** 对任意文本批量脱敏（手机号 / 身份证，基于模式识别），用于审计导出等场景。 */
    public static String maskText(String text) {
        if (text == null) {
            return null;
        }
        // 先脱敏身份证（更长、更具体），避免手机号正则误伤身份证内的数字串
        return maskPhone(maskIdCard(text));
    }
}
