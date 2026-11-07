package com.pida.app.data

import com.google.gson.annotations.SerializedName

data class HealthResponse(
    @SerializedName("ok") val ok: Boolean,
    @SerializedName("endpoints") val endpoints: Int = 0,
    @SerializedName("deps") val deps: String? = null,
    @SerializedName("groups") val groups: List<String>? = null
)
