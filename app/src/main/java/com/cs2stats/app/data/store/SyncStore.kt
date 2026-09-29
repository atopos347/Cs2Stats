package com.cs2stats.app.data.store

/**
 * 可插拔的同步存储：把「数据空间」抽象成读写若干个 JSON 文件。
 *
 * 实现方：
 * - [WebDavStore]：坚果云 / Nextcloud / 群晖等一切 WebDAV 服务（当前默认）；
 * - 自建后端：`data/remote/ApiClient.kt` 走 HTTP 接口，语义等价；
 * - GitHub 仓库：后续可直接加一个 `GitStore` 实现 raw 文件读写。
 *
 * 约定的文件（都放在 [root] 目录下，单写者模型：同一账号同一台手机为主）：
 * ```
 * profile.json   当前 Steam 资料
 * matches.json   { "matches": [ <match>, ... ] }
 * ```
 *
 * > 曾经还有 `inventory.json`（库存），2026-09-29 库存功能整体移除后**不再读写**；
 * > 网盘上若还留着旧文件，忽略即可（App 从没采集过库存，里面是 0 件物品）。
 */
interface SyncStore {

    /** 展示用标签，例如「dav.jianguoyun.com」。 */
    val label: String

    /** 读取文件；不存在返回 null，其余错误抛异常。 */
    suspend fun read(path: String): String?

    /** 写入文件（自动创建父目录）。 */
    suspend fun write(path: String, content: String)

    /** 连通性与凭据自测：成功返回 true。 */
    suspend fun test(): Boolean

    companion object {
        const val PROFILE = "profile.json"
        const val MATCHES = "matches.json"
    }
}
