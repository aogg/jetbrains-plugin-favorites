package com.aogg.favorites.helper

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.diagnostic.Logger

/**
 * 规则密码安全存储（1.4.0 SQL 规则新增）：
 * - SQL 手动连接密码经 PasswordSafe 存取，服务名固定为「Favorites SQL 规则: <规则id>」；
 * - 明文密码永不落项目 XML（[RuleStorageHelper] 已保证 sqlPassword 不做持久化字段）；
 * - 全部调用 runCatching，失败仅记日志并按空串处理，不影响其它功能。
 *
 * 线程约定：read 可能触发磁盘/钥匙串 IO，请在后台线程调用（执行器与「测试」按钮均为后台线程）；
 * save 建议也在后台线程调用（对话框 OK 后异步写入）。
 */
object RulePasswordStore {

    private val log = Logger.getInstance(RulePasswordStore::class.java)

    /** 手动连接用户名为空时 PasswordSafe Credentials 使用的占位用户名 */
    const val DEFAULT_CREDENTIAL_USER = "favorites"

    /**
     * 生成 PasswordSafe 服务名（读写必须使用同一 attributes，故统一收口此处）
     *
     * @param ruleId 规则 id
     */
    @JvmStatic
    fun serviceName(ruleId: String): String = "Favorites SQL 规则: $ruleId"

    /**
     * 保存 SQL 手动连接密码（已存密码直接覆盖；失败仅记日志，不中断调用方流程）
     *
     * @param ruleId 规则 id
     * @param user 手动连接用户名（空串时用 [DEFAULT_CREDENTIAL_USER] 占位）
     * @param password 明文密码
     */
    @JvmStatic
    fun save(ruleId: String, user: String, password: String) {
        // ////////////////  校验参数：空密码不写入（无意义且会覆盖旧值语义不明）  start  ///////
        if (ruleId.isBlank() || password.isEmpty()) return
        // ////////////////  校验参数  end  ////////////////////////////////////////////

        // ////////////////  核心逻辑：PasswordSafe 写入（失败仅记日志）  start  /////////////
        runCatching {
            val attributes = CredentialAttributes(serviceName(ruleId))
            val credentials = Credentials(
                user.takeIf { candidate -> candidate.isNotBlank() } ?: DEFAULT_CREDENTIAL_USER,
                password
            )
            // PasswordSafe 在 Kotlin 侧为伴生属性 instance（Java 静态转发名 getInstance 对 Kotlin 不可见）
            PasswordSafe.instance.set(attributes, credentials)
        }.onFailure { log.warn("保存 SQL 规则密码到 PasswordSafe 失败（ruleId=$ruleId）", it) }
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////
    }

    /**
     * 读取 SQL 手动连接密码（无存值/读取失败返回空串；失败仅记日志）
     *
     * @param ruleId 规则 id
     * @return 明文密码，未存或失败返回空串（调用方按空密码处理）
     */
    @JvmStatic
    fun read(ruleId: String): String {
        // ////////////////  校验参数  start  ///////////////////////////////////////////
        if (ruleId.isBlank()) return ""
        // ////////////////  校验参数  end  /////////////////////////////////////////////

        // ////////////////  返回：PasswordSafe 读取（失败兜底空串）  start  ////////////////
        return runCatching {
            val attributes = CredentialAttributes(serviceName(ruleId))
            PasswordSafe.instance.get(attributes)?.getPasswordAsString().orEmpty()
        }.onFailure { log.warn("从 PasswordSafe 读取 SQL 规则密码失败（ruleId=$ruleId）", it) }
            .getOrDefault("")
        // ////////////////  返回  end  ///////////////////////////////////////////////
    }
}
