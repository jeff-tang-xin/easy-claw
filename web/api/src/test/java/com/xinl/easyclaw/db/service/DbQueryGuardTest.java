package com.xinl.easyclaw.db.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DbQueryGuard#checkWrite} 回归：db_dml / db_ddl 的语句白名单防线。
 * <p>
 * 锁三件事：白名单方向正确（DML 不放 DDL、反之亦然、SELECT 一律拒）、
 * 多语句拼接拒绝、字符串字面量里的分号/关键词不误伤。
 * 与只读防线（{@code check}）方向相反：这里是「只允许指定类别的写」。
 */
@DisplayName("DbQueryGuard.checkWrite：写语句白名单防线")
class DbQueryGuardTest {

    private final DbQueryGuard guard = new DbQueryGuard();

    @Test
    @DisplayName("DML 白名单内放行（大小写不敏感）")
    void dmlAllowlistPasses() {
        assertThat(guard.checkWrite("INSERT INTO t(a) VALUES(1)",
                DbQueryGuard.DML_FIRST_WORDS, "DML").allowed()).isTrue();
        assertThat(guard.checkWrite("update t set a=1 where id=1",
                DbQueryGuard.DML_FIRST_WORDS, "DML").allowed()).isTrue();
        assertThat(guard.checkWrite("DELETE FROM t WHERE id=1",
                DbQueryGuard.DML_FIRST_WORDS, "DML").allowed()).isTrue();
        assertThat(guard.checkWrite(
                "MERGE INTO t USING s ON t.id=s.id WHEN MATCHED THEN UPDATE SET a=1",
                DbQueryGuard.DML_FIRST_WORDS, "DML").allowed()).isTrue();
    }

    @Test
    @DisplayName("DML 拒绝 SELECT 与 DDL 串味")
    void dmlRejectsSelectAndDdl() {
        assertThat(guard.checkWrite("SELECT * FROM t",
                DbQueryGuard.DML_FIRST_WORDS, "DML").allowed()).isFalse();
        assertThat(guard.checkWrite("DROP TABLE t",
                DbQueryGuard.DML_FIRST_WORDS, "DML").allowed()).isFalse();
        assertThat(guard.checkWrite("TRUNCATE TABLE t",
                DbQueryGuard.DML_FIRST_WORDS, "DML").allowed()).isFalse();
    }

    @Test
    @DisplayName("DDL 白名单内放行；拒绝 DML 与 SELECT")
    void ddlAllowlistAndRejects() {
        assertThat(guard.checkWrite("CREATE TABLE t(id INT)",
                DbQueryGuard.DDL_FIRST_WORDS, "DDL").allowed()).isTrue();
        assertThat(guard.checkWrite("ALTER TABLE t ADD COLUMN c INT",
                DbQueryGuard.DDL_FIRST_WORDS, "DDL").allowed()).isTrue();
        assertThat(guard.checkWrite("drop index idx",
                DbQueryGuard.DDL_FIRST_WORDS, "DDL").allowed()).isTrue();
        assertThat(guard.checkWrite("TRUNCATE TABLE t",
                DbQueryGuard.DDL_FIRST_WORDS, "DDL").allowed()).isTrue();

        assertThat(guard.checkWrite("UPDATE t SET a=1",
                DbQueryGuard.DDL_FIRST_WORDS, "DDL").allowed()).isFalse();
        assertThat(guard.checkWrite("SELECT 1",
                DbQueryGuard.DDL_FIRST_WORDS, "DDL").allowed()).isFalse();
    }

    @Test
    @DisplayName("多语句拼接拒绝（分号后跟第二条）；末尾单个分号合法")
    void multiStatementRejected() {
        assertThat(guard.checkWrite("UPDATE t SET a=1; DROP TABLE t",
                DbQueryGuard.DML_FIRST_WORDS, "DML").allowed()).isFalse();
        assertThat(guard.checkWrite("UPDATE t SET a=1 WHERE id=1;",
                DbQueryGuard.DML_FIRST_WORDS, "DML").allowed()).isTrue();
    }

    @Test
    @DisplayName("字符串字面量里的分号与关键词不误伤")
    void stringLiteralsDoNotTrigger() {
        assertThat(guard.checkWrite("UPDATE t SET remark='a;b(drop)' WHERE id=1",
                DbQueryGuard.DML_FIRST_WORDS, "DML").allowed()).isTrue();
    }

    @Test
    @DisplayName("注释里的写关键词不影响首词判定；空 / 仅注释拒绝")
    void commentsAndBlankHandled() {
        // 剥注释后首词是 update → 放行（checkWrite 不做危险子句匹配——写工具本就允许写关键词）
        assertThat(guard.checkWrite("/* drop table */ UPDATE t SET a=1 WHERE id=1",
                DbQueryGuard.DML_FIRST_WORDS, "DML").allowed()).isTrue();
        assertThat(guard.checkWrite("", DbQueryGuard.DML_FIRST_WORDS, "DML").allowed()).isFalse();
        assertThat(guard.checkWrite("-- just a comment",
                DbQueryGuard.DML_FIRST_WORDS, "DML").allowed()).isFalse();
    }

    @Test
    @DisplayName("UPDATE/DELETE 缺顶层 WHERE 拒绝（防全表改/删）；子查询内的 WHERE 不算数")
    void updateDeleteRequireTopLevelWhere() {
        assertThat(guard.checkWrite("UPDATE t SET a=1",
                DbQueryGuard.DML_FIRST_WORDS, "DML").allowed()).isFalse();
        assertThat(guard.checkWrite("DELETE FROM t",
                DbQueryGuard.DML_FIRST_WORDS, "DML").allowed()).isFalse();
        // 子查询里有 WHERE 但主语句没有 → 仍判全表更新
        assertThat(guard.checkWrite("UPDATE t SET a=(SELECT MAX(x) FROM s WHERE id=1)",
                DbQueryGuard.DML_FIRST_WORDS, "DML").allowed()).isFalse();
        // 顶层 WHERE → 放行
        assertThat(guard.checkWrite("UPDATE t SET a=(SELECT MAX(x) FROM s WHERE id=1) WHERE t.id=2",
                DbQueryGuard.DML_FIRST_WORDS, "DML").allowed()).isTrue();
        assertThat(guard.checkWrite("DELETE FROM t WHERE created_at < '2020-01-01'",
                DbQueryGuard.DML_FIRST_WORDS, "DML").allowed()).isTrue();
    }

    @Test
    @DisplayName("MySQL 可执行注释拒绝；Oracle 优化器 hint 不误伤")
    void executableCommentRejectedButHintFine() {
        // /*!50000 ...*/ 剥注释后不可见但 MySQL 会真实执行——原文含即拒
        assertThat(guard.checkWrite("/*!50000 DROP TABLE t*/",
                DbQueryGuard.DDL_FIRST_WORDS, "DDL").allowed()).isFalse();
        assertThat(guard.checkWrite("UPDATE t SET a=1 WHERE id=1 /*!80000 index hint*/",
                DbQueryGuard.DML_FIRST_WORDS, "DML").allowed()).isFalse();
        // Oracle hint（/*+ ... */）不执行 SQL，不误伤
        assertThat(guard.checkWrite("UPDATE /*+ PARALLEL(t 4) */ t SET a=1 WHERE id=1",
                DbQueryGuard.DML_FIRST_WORDS, "DML").allowed()).isTrue();
    }

    @Test
    @DisplayName("库级语句拒绝（MySQL/PG/SQL Server 的 DATABASE/SCHEMA）")
    void databaseLevelRejected() {
        assertThat(guard.checkWrite("DROP DATABASE orders",
                DbQueryGuard.DDL_FIRST_WORDS, "DDL").allowed()).isFalse();
        assertThat(guard.checkWrite("CREATE DATABASE orders",
                DbQueryGuard.DDL_FIRST_WORDS, "DDL").allowed()).isFalse();
        assertThat(guard.checkWrite("ALTER SCHEMA s RENAME TO s2",
                DbQueryGuard.DDL_FIRST_WORDS, "DDL").allowed()).isFalse();
    }

    @Test
    @DisplayName("代码/权限/实例级对象拒绝（全方言）")
    void forbiddenObjectsRejected() {
        // 代码对象：函数体=任意执行体（PG CREATE OR REPLACE 兼容）
        assertThat(guard.checkWrite(
                "CREATE FUNCTION f() RETURNS INT AS $$ BEGIN RETURN 1; END $$ LANGUAGE plpgsql",
                DbQueryGuard.DDL_FIRST_WORDS, "DDL").allowed()).isFalse();
        assertThat(guard.checkWrite("CREATE OR REPLACE FUNCTION f() RETURNS INT RETURN 1",
                DbQueryGuard.DDL_FIRST_WORDS, "DDL").allowed()).isFalse();
        assertThat(guard.checkWrite("CREATE PROCEDURE p() BEGIN DELETE FROM t; END",
                DbQueryGuard.DDL_FIRST_WORDS, "DDL").allowed()).isFalse();
        assertThat(guard.checkWrite(
                "CREATE TRIGGER trg AFTER INSERT ON t FOR EACH ROW EXECUTE FUNCTION f()",
                DbQueryGuard.DDL_FIRST_WORDS, "DDL").allowed()).isFalse();
        assertThat(guard.checkWrite("CREATE EVENT e ON SCHEDULE EVERY 1 DAY DO DELETE FROM t",
                DbQueryGuard.DDL_FIRST_WORDS, "DDL").allowed()).isFalse();
        assertThat(guard.checkWrite("DROP FUNCTION f",
                DbQueryGuard.DDL_FIRST_WORDS, "DDL").allowed()).isFalse();
        // 权限对象（Oracle USER / SQL Server LOGIN,USER / PG ROLE / MySQL USER）
        assertThat(guard.checkWrite("CREATE USER u IDENTIFIED BY 'x'",
                DbQueryGuard.DDL_FIRST_WORDS, "DDL").allowed()).isFalse();
        assertThat(guard.checkWrite("CREATE LOGIN u WITH PASSWORD = 'x'",
                DbQueryGuard.DDL_FIRST_WORDS, "DDL").allowed()).isFalse();
        assertThat(guard.checkWrite("CREATE ROLE r",
                DbQueryGuard.DDL_FIRST_WORDS, "DDL").allowed()).isFalse();
        assertThat(guard.checkWrite("DROP USER u CASCADE",
                DbQueryGuard.DDL_FIRST_WORDS, "DDL").allowed()).isFalse();
        assertThat(guard.checkWrite("RENAME USER a TO b",
                DbQueryGuard.DDL_FIRST_WORDS, "DDL").allowed()).isFalse();
        // Oracle 会话/实例级
        assertThat(guard.checkWrite("ALTER SESSION SET current_schema=other",
                DbQueryGuard.DDL_FIRST_WORDS, "DDL").allowed()).isFalse();
        assertThat(guard.checkWrite("ALTER SYSTEM KILL SESSION '1,2'",
                DbQueryGuard.DDL_FIRST_WORDS, "DDL").allowed()).isFalse();
    }

    @Test
    @DisplayName("表名恰为保留词不误伤；正当 DDL 放行")
    void objectNamesThatLookLikeKeywordsDoNotFalsePositive() {
        assertThat(guard.checkWrite("ALTER TABLE \"user\" ADD COLUMN c INT",
                DbQueryGuard.DDL_FIRST_WORDS, "DDL").allowed()).isTrue();
        // user_backup 是完整标识符（\b 不在 user 与下划线之间），不触发权限对象拦截
        assertThat(guard.checkWrite("DROP TABLE user_backup",
                DbQueryGuard.DDL_FIRST_WORDS, "DDL").allowed()).isTrue();
        assertThat(guard.checkWrite("RENAME TABLE t1 TO t2",
                DbQueryGuard.DDL_FIRST_WORDS, "DDL").allowed()).isTrue();
        assertThat(guard.checkWrite("CREATE INDEX idx ON t(col)",
                DbQueryGuard.DDL_FIRST_WORDS, "DDL").allowed()).isTrue();
    }

    @Test
    @DisplayName("方言特有写/执行首词全部拒绝（PG CTE 写、匿名块、CALL/EXEC、LOAD、PREPARE、SET、USE 等）")
    void dialectSpecificFirstWordsRejected() {
        String[] rejected = {
                "WITH x AS (DELETE FROM t RETURNING *) INSERT INTO log SELECT * FROM x", // PG CTE 写
                "DO $$ BEGIN DELETE FROM t; END $$",   // PG 匿名块
                "BEGIN DELETE FROM t; END;",           // Oracle / SQL Server 匿名块
                "CALL p()",                            // 存储过程调用
                "EXEC p",                              // SQL Server
                "LOAD DATA INFILE '/x' INTO TABLE t",  // MySQL
                "PREPARE s FROM 'DELETE FROM t'",      // MySQL 动态 SQL
                "SET FOREIGN_KEY_CHECKS=0",            // MySQL
                "USE other_db",                        // MySQL / SQL Server
                "LOCK TABLES t WRITE",                 // MySQL
                "FLUSH PRIVILEGES",                    // MySQL
                "KILL 123",                            // MySQL
                "VACUUM t",                            // PG
                "REFRESH MATERIALIZED VIEW v",         // PG
                "GRANT ALL ON t TO u",                 // 权限
        };
        for (String sql : rejected) {
            assertThat(guard.checkWrite(sql, DbQueryGuard.DML_FIRST_WORDS, "DML").allowed())
                    .as("DML 工具应拒绝: %s", sql).isFalse();
        }
    }
}
