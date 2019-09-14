package com.dc2f.api.edit.dto

import com.dc2f.ContentDef
import com.dc2f.util.ApiDto

@ApiDto
open class ErrorResponse(val message: String)

@ApiDto
data class InspectDto(
    val content: ContentDef
)

class Dc2fApi {
    companion object {
        const val HEADER_TRANSACTION = "x-transaction"
    }
}
