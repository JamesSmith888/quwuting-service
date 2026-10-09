package org.quwuting.quwutingservice.spend;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 账目存储约束镜像校验（2026-10-01，零依赖：读 DDL 文本断言）。
 * <p>
 * {@link SpendEntryLimits} 是校验与实体列定义的唯一声明处，但 DDL 在迁移文件里——两者漂移
 * （有人改了列宽却没改常量）正是「校验比存储宽 ⇒ 落库异常回滚整批 ⇒ 账目永远上不了云」
 * 的成因。本测试把常量与 {@code V16__spend_entries.sql} 逐项对齐；若后续迁移改了列定义，
 * 须同步更新常量并把断言指向新的迁移文件。
 */
class SpendEntryLimitsMirrorTest {

    private static final Path DDL = Path.of("src/main/resources/db/migration-mysql/V16__spend_entries.sql");

    @Test
    void constantsMatchSpendEntriesDdl() throws IOException {
        String ddl = Files.readString(DDL);

        Matcher amount = Pattern.compile("\\bamount\\s+decimal\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)",
                Pattern.CASE_INSENSITIVE).matcher(ddl);
        assertTrue(amount.find(), "V16 中找不到 amount decimal(p,s) 列定义");
        assertEquals(SpendEntryLimits.AMOUNT_PRECISION, Integer.parseInt(amount.group(1)));
        assertEquals(SpendEntryLimits.AMOUNT_SCALE, Integer.parseInt(amount.group(2)));

        assertEquals(SpendEntryLimits.CLIENT_ENTRY_ID_MAX_LENGTH, varcharLength(ddl, "client_entry_id"));
        assertEquals(SpendEntryLimits.SOURCE_REF_ID_MAX_LENGTH, varcharLength(ddl, "source_ref_id"));
        assertEquals(SpendEntryLimits.VENUE_NAME_MAX_LENGTH, varcharLength(ddl, "venue_name"));
    }

    @Test
    void companionsJsonLimitMatchesV47Ddl() throws IOException {
        // V47（2026-10-09）新增 companions_json varchar(n)：常量与 DDL 逐项对齐，沿用本类的防漂移纪律
        String ddl = Files.readString(Path.of("src/main/resources/db/migration-mysql/V47__spend_entry_companions.sql"));
        Matcher m = Pattern.compile("\\bcompanions_json\\s+varchar\\(\\s*(\\d+)\\s*\\)", Pattern.CASE_INSENSITIVE)
                .matcher(ddl);
        assertTrue(m.find(), "V47 中找不到 companions_json varchar(n) 列定义");
        assertEquals(SpendEntryLimits.COMPANIONS_JSON_MAX_LENGTH, Integer.parseInt(m.group(1)));
    }

    @Test
    void amountMaxIsLargestValueOfColumn() {
        int integerDigits = SpendEntryLimits.AMOUNT_PRECISION - SpendEntryLimits.AMOUNT_SCALE;
        BigDecimal expected = BigDecimal.TEN.pow(integerDigits)
                .subtract(BigDecimal.ONE.movePointLeft(SpendEntryLimits.AMOUNT_SCALE));
        assertEquals(0, expected.compareTo(SpendEntryLimits.AMOUNT_MAX),
                "AMOUNT_MAX 必须等于 decimal(precision, scale) 的最大可表示值");
    }

    private static int varcharLength(String ddl, String column) {
        Matcher m = Pattern.compile("\\b" + column + "\\s+varchar\\(\\s*(\\d+)\\s*\\)", Pattern.CASE_INSENSITIVE)
                .matcher(ddl);
        assertTrue(m.find(), "V16 中找不到 " + column + " varchar(n) 列定义");
        return Integer.parseInt(m.group(1));
    }
}
