// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.http

import com.google.protobuf.util.JsonFormat
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.multipart.Multipart
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext
import kotlinx.coroutines.future.future
import kotlinx.coroutines.CoroutineScope
import org.slf4j.LoggerFactory
import panelarr.mihon.v1.installExtensionResponse
import panelarr.mihonsource.api.toProto
import panelarr.mihonsource.extension.ExtensionException
import panelarr.mihonsource.extension.ExtensionInstaller
import panelarr.mihonsource.extension.Expectations

/**
 * `POST /v1/admin/extensions/upload`: installs an extension from a multipart form, which
 * is easier for browsers and curl than base64 in JSON. Field `file` holds the jar or APK;
 * optional fields `expectedSha256` and `expectedSigningFingerprint` work as in
 * InstallExtension. Responds with the same JSON as InstallExtension.
 */
class UploadService(
    private val installer: ExtensionInstaller,
    private val scope: CoroutineScope,
) : HttpService {
    private val log = LoggerFactory.getLogger(UploadService::class.java)
    private val printer = JsonFormat.printer().omittingInsignificantWhitespace()

    override fun serve(
        ctx: ServiceRequestContext,
        req: HttpRequest,
    ): HttpResponse {
        val contentType = req.contentType()
        if (contentType == null || !contentType.`is`(MediaType.MULTIPART_FORM_DATA)) {
            return HttpAuth.error(HttpStatus.BAD_REQUEST, "send the file as multipart/form-data in a field named 'file'")
        }
        val result =
            Multipart.from(req).aggregate().thenCompose { form ->
                scope.future {
                    val file = form.field("file") ?: return@future HttpAuth.error(HttpStatus.BAD_REQUEST, "the 'file' field is missing")
                    val expectations =
                        Expectations(
                            sha256 = form.field("expectedSha256")?.contentUtf8()?.ifBlank { null },
                            signingFingerprint = form.field("expectedSigningFingerprint")?.contentUtf8()?.ifBlank { null },
                        )
                    try {
                        val installed = installer.installFromBytes(file.content().array(), file.filename() ?: "upload", expectations)
                        val json = printer.print(installExtensionResponse { extension = installed.toProto() })
                        HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, json)
                    } catch (e: ExtensionException) {
                        HttpAuth.error(HttpStatus.BAD_REQUEST, e.message ?: "invalid extension")
                    } catch (e: IllegalArgumentException) {
                        HttpAuth.error(HttpStatus.BAD_REQUEST, e.message ?: "invalid request")
                    } catch (
                        @Suppress("TooGenericExceptionCaught") e: Exception,
                    ) {
                        log.error("Upload failed", e)
                        HttpAuth.error(HttpStatus.INTERNAL_SERVER_ERROR, "internal error")
                    }
                }
            }
        return HttpResponse.of(result)
    }
}
