package cn.assetinventory.app

sealed interface QrPayload {
    data class Area(val code: String) : QrPayload
    data class Asset(val code: String) : QrPayload
    data class Invalid(val reason: String) : QrPayload

    companion object {
        // 兼容真实台账格式：BJB030、DP0001、NM-003、DP042-1、ZJ0061-1。
        // 横线可以分隔字母前缀，也可以表示同主编号下的独立子编号。
        private val assetPattern = Regex("^[A-Z]{2,6}-?\\d{3,8}(?:-\\d{1,3})?$")
        private val areaPattern = Regex("^[A-Z0-9_-]{2,32}$")

        fun parse(raw: String): QrPayload {
            val value = raw
                .replace('\uFF1A', ':')
                .replace("\uFEFF", "")
                .replace("\u200B", "")
                .replace("\u200C", "")
                .replace("\u200D", "")
                .trim()
            val plainAsset = value.uppercase()
            if (assetPattern.matches(plainAsset)) return Asset(plainAsset)
            val parts = value.split(':', limit = 2)
            if (parts.size != 2) return Invalid(
                "二维码格式不正确。区域码示例：AREA:ITOPERATION；资产码示例：ASSET:DP0001"
            )
            val type = parts[0].uppercase()
            val code = parts[1].trim().uppercase()
            return when {
                type == "ASSET" && assetPattern.matches(code) -> Asset(code)
                type == "AREA" && areaPattern.matches(code) -> Area(code)
                type == "ASSET" -> Invalid(
                    "资产编号格式不正确，例如 BJB030、DP0001、NM-003 或 DP042-1"
                )
                type == "AREA" -> Invalid(
                    "区域码格式：AREA:区域编号，例如 AREA:ITOPERATION。编号只能使用字母、数字、横线或下划线，不能有空格"
                )
                else -> Invalid("不是本系统二维码。区域码应以 AREA: 开头，资产码应以 ASSET: 开头")
            }
        }
    }
}
