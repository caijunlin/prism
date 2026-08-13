package com.github.caijunlin.prism.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import java.io.File
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

/**
 * @author : caijunlin
 * @date   : 2026/3/25
 * @description   : X5激活码管理
 */
object LicenseManager {

    private const val PREF_NAME = "x5_auth_config"
    private const val AUTH = "key"
    private const val STATUS = "status"

    /**
     * 在你的 Library 初始化 X5 之前调用此方法
     * @param context 环境上下文
     * @param authCode 你当前最新传入的激活码
     */
    fun resetAuth(context: Context, authCode: String) {
        // 将当前传入的激活码进行 SHA-256 单向哈希加密
        val authCodeHash = hashWithSHA256(authCode)
        val sp: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val oldAuthCodeHash = sp.getString(AUTH, "") ?: ""
        // 对比哈希值直接使用新值检查不判断是否存在老的激活码
        if (oldAuthCodeHash.isNotEmpty() && authCodeHash != oldAuthCodeHash) {
            // 激活码发生了改变，物理删除 X5 的核心文件夹 (app_tbs)
            val dir = File(context.applicationInfo.dataDir, "app_tbs")
            Log.d("Prism", "License changed delete X5 core folder: $dir")
            dir.deleteRecursively()
        }
        sp.edit { putString(AUTH, authCodeHash) }
    }

    /**
     * 保存当前激活码的授权状态
     * @param context 环境上下文
     * @param success 激活码授权状态
     */
    fun saveAuthorizedState(context: Context, success: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(STATUS, success)
        }
    }

    /**
     * 获取当前激活码的授权状态
     * @param context 环境上下文
     * @param authCode 当前传入的激活码
     * @return 激活码授权状态
     */
    fun isAuthorized(context: Context, authCode: String): Boolean {
        if (authCode.isEmpty()) return false
        // X5文件夹必须存在
        val tbsDir = File(context.applicationInfo.dataDir, "app_tbs")
        if (!tbsDir.exists()) {
            return false
        }
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return sp.getBoolean(STATUS, false)
                &&
                sp.getString(AUTH, "").equals(
                    hashWithSHA256(authCode)
                )
    }

    /**
     * 单向不可逆加密：SHA-256 哈希算法
     */
    private fun hashWithSHA256(input: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(input.toByteArray())
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: NoSuchAlgorithmException) {
            e.printStackTrace()
            // 极端情况下不支持 SHA-256 时的降级方案
            input.hashCode().toString()
        }
    }

}