package com.m57.hermescontrol.ui.chat

import android.util.Log
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.model.ChatImageUploadResponse
import com.m57.hermescontrol.data.model.ManagedFileActionResponse
import com.m57.hermescontrol.data.model.ManagedFileDelete
import com.m57.hermescontrol.data.model.ManagedFileEntry
import com.m57.hermescontrol.data.model.ManagedFilesListResponse
import com.m57.hermescontrol.data.remote.HermesApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.util.Base64

class HttpChatAttachmentStagerTest {
    private val api = mockk<HermesApiService>()
    private val stager = HttpChatAttachmentStager(api = { api }, profile = { "work" }, newUploadId = { "u1" })
    private val bytes = ByteArray(64 * 1024) { (it % 251).toByte() }

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun largeFileIsStreamedIntoAStagingDirectoryUnderTheManagedRoot() =
        runTest {
            coEvery { api.listManagedFiles(null) } returns Response.success(ManagedFilesListResponse(path = "/home/u"))
            val path = slot<RequestBody>()
            val overwrite = slot<RequestBody>()
            val file = slot<MultipartBody.Part>()
            coEvery { api.uploadManagedFileStream(capture(path), capture(overwrite), capture(file)) } answers {
                Response.success(
                    ManagedFileActionResponse(
                        ok = true,
                        path = "/home/u/.hermes-mobile-uploads/u1/My Report.pdf",
                        entry = ManagedFileEntry(path = "/home/u/.hermes-mobile-uploads/u1/My Report.pdf"),
                    ),
                )
            }

            val result =
                stager.stage(
                    Attachment("content://x", "My Report.pdf", "application/pdf"),
                ) { bytes.inputStream() }

            assertEquals(
                GatewayStageResult.Staged(
                    GatewayStagedAttachment(
                        path = "/home/u/.hermes-mobile-uploads/u1/My Report.pdf",
                        cleanupPath = "/home/u/.hermes-mobile-uploads/u1",
                    ),
                ),
                result,
            )
            assertEquals("/home/u/.hermes-mobile-uploads/u1/My Report.pdf", path.captured.utf8())
            assertEquals("false", overwrite.captured.utf8())
            assertArrayEquals(bytes, Buffer().also { file.captured.body.writeTo(it) }.readByteArray())
        }

    @Test
    fun lockedRootIsPreferredOverTheListedPath() =
        runTest {
            coEvery { api.listManagedFiles(null) } returns
                Response.success(ManagedFilesListResponse(path = "/opt/data/projects", lockedRoot = "/opt/data/"))
            val path = slot<RequestBody>()
            coEvery { api.uploadManagedFileStream(capture(path), any(), any()) } returns
                Response.success(ManagedFileActionResponse(ok = true))

            stager.stage(Attachment("content://x", "a.zip", "application/zip")) { bytes.inputStream() }

            assertEquals("/opt/data/.hermes-mobile-uploads/u1/a.zip", path.captured.utf8())
        }

    @Test
    fun gatewaySizeRefusalCarriesItsDetailAndCleansUp() =
        runTest {
            coEvery { api.listManagedFiles(null) } returns Response.success(ManagedFilesListResponse(path = "/home/u"))
            coEvery { api.uploadManagedFileStream(any(), any(), any()) } returns
                Response.error(413, """{"detail":"File is too large"}""".toResponseBody())
            coEvery { api.deleteManagedFile(any()) } returns Response.success(ManagedFileActionResponse(ok = true))

            val result =
                stager.stage(
                    Attachment("content://x", "big.iso", "application/octet-stream"),
                ) { bytes.inputStream() }

            assertEquals(GatewayStageResult.TooLarge("File is too large"), result)
            coVerify(exactly = 1) {
                api.deleteManagedFile(ManagedFileDelete("/home/u/.hermes-mobile-uploads/u1", recursive = true))
            }
        }

    @Test
    fun missingRouteIsUnsupported() =
        runTest {
            coEvery { api.listManagedFiles(null) } returns Response.error(404, "{}".toResponseBody())

            val result =
                stager.stage(
                    Attachment("content://x", "a.bin", "application/octet-stream"),
                ) { bytes.inputStream() }

            assertEquals(GatewayStageResult.Unsupported, result)
        }

    @Test
    fun largeImageIsUploadedAsAStreamedDataUrlForTheActiveProfile() =
        runTest {
            val body = slot<RequestBody>()
            coEvery { api.uploadChatImage("work", capture(body)) } returns
                Response.success(ChatImageUploadResponse(ok = true, path = "/h/images/dashboard_x.jpg"))

            val result = stager.stage(Attachment("content://x", "pic \"1\".jpg", "image/jpeg")) { bytes.inputStream() }

            assertEquals(
                GatewayStageResult.Staged(GatewayStagedAttachment("/h/images/dashboard_x.jpg", cleanupPath = null)),
                result,
            )
            val json = Json.parseToJsonElement(body.captured.utf8()).jsonObject
            val dataUrl = json.getValue("data_url").jsonPrimitive.content
            assertEquals("pic \"1\".jpg", json.getValue("filename").jsonPrimitive.content)
            assertEquals("data:image/jpeg;base64,", dataUrl.substringBefore(",") + ",")
            assertArrayEquals(bytes, Base64.getDecoder().decode(dataUrl.substringAfter(",")))
        }

    @Test
    fun imageSizeRefusalCarriesTheGatewayDetail() =
        runTest {
            coEvery { api.uploadChatImage(any(), any()) } returns
                Response.error(413, """{"detail":"Image is too large; cap is 25 MB"}""".toResponseBody())

            val result = stager.stage(Attachment("content://x", "pic.png", "image/png")) { bytes.inputStream() }

            assertEquals(GatewayStageResult.TooLarge("Image is too large; cap is 25 MB"), result)
        }

    @Test
    fun stagedFileNameKeepsTheNameButNeverAPathSegment() {
        assertEquals("My Report.pdf", stagedFileName("My Report.pdf"))
        assertEquals("b.txt", stagedFileName("a/b.txt"))
        assertEquals("c.txt", stagedFileName("a\\c.txt"))
        assertEquals("attachment", stagedFileName(".."))
        assertEquals("attachment", stagedFileName("  "))
        assertEquals("x_y", stagedFileName("x\ny"))
    }

    private fun RequestBody.utf8(): String = Buffer().also { writeTo(it) }.readUtf8()
}
