package com.m57.hermescontrol.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class RetryPolicyTest {
    // ── Default SafeApiCall Mutation Tests: retries = 0, exactly 1 call ──────

    @Test
    fun safeApiCallDefaultsToZeroRetriesOnIoException() =
        runTest {
            var callCount = 0
            val result =
                safeApiCall<String> {
                    callCount++
                    throw IOException("Network dropped")
                }
            assertTrue(result is NetworkResult.Failure)
            val error = (result as NetworkResult.Failure).error
            assertTrue(error is NetworkError.Connection)
            assertEquals(1, callCount)
        }

    @Test
    fun safeApiCallDefaultsToZeroRetriesOnSocketTimeoutException() =
        runTest {
            var callCount = 0
            val result =
                safeApiCall<String> {
                    callCount++
                    throw SocketTimeoutException("Read timeout")
                }
            assertTrue(result is NetworkResult.Failure)
            val error = (result as NetworkResult.Failure).error
            assertTrue(error is NetworkError.Connection)
            assertEquals(1, callCount)
        }

    @Test
    fun safeApiCallDefaultsToZeroRetriesOnHttp500() =
        runTest {
            var callCount = 0
            val result =
                safeApiCall<String> {
                    callCount++
                    Response.error(500, "Server Error".toResponseBody("text/plain".toMediaTypeOrNull()))
                }
            assertTrue(result is NetworkResult.Failure)
            val error = (result as NetworkResult.Failure).error
            assertTrue(error is NetworkError.Http)
            assertEquals(500, (error as NetworkError.Http).code)
            assertEquals(1, callCount)
        }

    @Test
    fun safeApiCallDefaultsToZeroRetriesOnHttp503() =
        runTest {
            var callCount = 0
            val result =
                safeApiCall<String> {
                    callCount++
                    Response.error(503, "Service Unavailable".toResponseBody("text/plain".toMediaTypeOrNull()))
                }
            assertTrue(result is NetworkResult.Failure)
            val error = (result as NetworkResult.Failure).error
            assertTrue(error is NetworkError.Http)
            assertEquals(503, (error as NetworkError.Http).code)
            assertEquals(1, callCount)
        }

    @Test
    fun safeApiCallDefaultsToZeroRetriesOnHttp429() =
        runTest {
            var callCount = 0
            val result =
                safeApiCall<String> {
                    callCount++
                    Response.error(429, "Too Many Requests".toResponseBody("text/plain".toMediaTypeOrNull()))
                }
            assertTrue(result is NetworkResult.Failure)
            val error = (result as NetworkResult.Failure).error
            assertTrue(error is NetworkError.Http)
            assertEquals(429, (error as NetworkError.Http).code)
            assertEquals(1, callCount)
        }

    // ── SafeApiRead Tests: retries = 2 default (up to 3 total attempts) ──────

    @Test
    fun safeApiReadRetriesUpToThreeAttemptsOnIoException() =
        runTest {
            var callCount = 0
            val result =
                safeApiRead<String> {
                    callCount++
                    throw IOException("Network dropped")
                }
            assertTrue(result is NetworkResult.Failure)
            val error = (result as NetworkResult.Failure).error
            assertTrue(error is NetworkError.Connection)
            assertEquals(3, callCount)
        }

    @Test
    fun safeApiReadRetriesUpToThreeAttemptsOnSocketTimeoutException() =
        runTest {
            var callCount = 0
            val result =
                safeApiRead<String> {
                    callCount++
                    throw SocketTimeoutException("Read timeout")
                }
            assertTrue(result is NetworkResult.Failure)
            val error = (result as NetworkResult.Failure).error
            assertTrue(error is NetworkError.Connection)
            assertEquals(3, callCount)
        }

    @Test
    fun safeApiReadRetriesUpToThreeAttemptsOnHttp500() =
        runTest {
            var callCount = 0
            val result =
                safeApiRead<String> {
                    callCount++
                    Response.error(500, "Server Error".toResponseBody("text/plain".toMediaTypeOrNull()))
                }
            assertTrue(result is NetworkResult.Failure)
            val error = (result as NetworkResult.Failure).error
            assertTrue(error is NetworkError.Http)
            assertEquals(500, (error as NetworkError.Http).code)
            assertEquals(3, callCount)
        }

    @Test
    fun safeApiReadRetriesUpToThreeAttemptsOnHttp429() =
        runTest {
            var callCount = 0
            val result =
                safeApiRead<String> {
                    callCount++
                    Response.error(429, "Rate Limited".toResponseBody("text/plain".toMediaTypeOrNull()))
                }
            assertTrue(result is NetworkResult.Failure)
            val error = (result as NetworkResult.Failure).error
            assertTrue(error is NetworkError.Http)
            assertEquals(429, (error as NetworkError.Http).code)
            assertEquals(3, callCount)
        }

    @Test
    fun safeApiReadSucceedsOnSecondAttemptAfterIoException() =
        runTest {
            var callCount = 0
            val result =
                safeApiRead<String> {
                    callCount++
                    if (callCount == 1) {
                        throw IOException("Transient connection drop")
                    } else {
                        Response.success("read-success")
                    }
                }
            assertTrue(result is NetworkResult.Success)
            assertEquals("read-success", (result as NetworkResult.Success).data)
            assertEquals(2, callCount)
        }

    @Test
    fun safeApiReadSucceedsOnThirdAttemptAfter5xx() =
        runTest {
            var callCount = 0
            val result =
                safeApiRead<String> {
                    callCount++
                    if (callCount < 3) {
                        Response.error(502, "Bad Gateway".toResponseBody("text/plain".toMediaTypeOrNull()))
                    } else {
                        Response.success("recovered-data")
                    }
                }
            assertTrue(result is NetworkResult.Success)
            assertEquals("recovered-data", (result as NetworkResult.Success).data)
            assertEquals(3, callCount)
        }

    // ── Explicit Retries = 2 on safeApiCall Still Performs 3 Attempts ─────────

    @Test
    fun safeApiCallWithExplicitRetries2PerformsThreeAttemptsOnIoException() =
        runTest {
            var callCount = 0
            val result =
                safeApiCall<String>(retries = 2) {
                    callCount++
                    throw IOException("Network error")
                }
            assertTrue(result is NetworkResult.Failure)
            assertEquals(3, callCount)
        }

    @Test
    fun safeApiCallWithExplicitRetries2PerformsThreeAttemptsOnHttp500() =
        runTest {
            var callCount = 0
            val result =
                safeApiCall<String>(retries = 2) {
                    callCount++
                    Response.error(500, "Server Error".toResponseBody("text/plain".toMediaTypeOrNull()))
                }
            assertTrue(result is NetworkResult.Failure)
            assertEquals(3, callCount)
        }

    // ── Non-Retryable Failures: Even With retries = 2, Exactly 1 Call ─────────

    @Test
    fun nonRetryable401AuthExpiredDoesNotRetry() =
        runTest {
            var callCount = 0
            val result =
                safeApiRead<String>(retries = 2) {
                    callCount++
                    Response.error(401, "Unauthorized".toResponseBody("text/plain".toMediaTypeOrNull()))
                }
            assertTrue(result is NetworkResult.Failure)
            val error = (result as NetworkResult.Failure).error
            assertTrue(error is NetworkError.AuthExpired)
            assertEquals(1, callCount)
        }

    @Test
    fun nonRetryable404NotFoundDoesNotRetry() =
        runTest {
            var callCount = 0
            val result =
                safeApiRead<String>(retries = 2) {
                    callCount++
                    Response.error(404, "Not Found".toResponseBody("text/plain".toMediaTypeOrNull()))
                }
            assertTrue(result is NetworkResult.Failure)
            val error = (result as NetworkResult.Failure).error
            assertTrue(error is NetworkError.Http)
            assertEquals(404, (error as NetworkError.Http).code)
            assertEquals(1, callCount)
        }

    @Test
    fun nonRetryableUnknownHostExceptionDoesNotRetry() =
        runTest {
            var callCount = 0
            val result =
                safeApiRead<String>(retries = 2) {
                    callCount++
                    throw UnknownHostException("No such host")
                }
            assertTrue(result is NetworkResult.Failure)
            val error = (result as NetworkResult.Failure).error
            assertTrue(error is NetworkError.Connection)
            assertEquals(1, callCount)
        }

    @Test
    fun nonRetryableSslExceptionDoesNotRetry() =
        runTest {
            var callCount = 0
            val result =
                safeApiRead<String>(retries = 2) {
                    callCount++
                    throw SSLException("TLS handshake failed")
                }
            assertTrue(result is NetworkResult.Failure)
            val error = (result as NetworkResult.Failure).error
            assertTrue(error is NetworkError.Connection)
            assertEquals(1, callCount)
        }

    @Test
    fun nonRetryableRuntimeExceptionDoesNotRetry() =
        runTest {
            var callCount = 0
            val result =
                safeApiRead<String>(retries = 2) {
                    callCount++
                    throw IllegalStateException("State invalid")
                }
            assertTrue(result is NetworkResult.Failure)
            val error = (result as NetworkResult.Failure).error
            assertTrue(error is NetworkError.Unknown)
            assertEquals(1, callCount)
        }

    // ── Cancellation Propagation and No Subsequent Attempts ─────────────────

    @Test
    fun cancellationWhileExecutingAttemptPropagatesAndNeverTriggersNextAttempt() =
        runTest {
            var callCount = 0
            var job: Job? = null
            var caughtCancellation: CancellationException? = null

            job =
                launch {
                    try {
                        safeApiRead<String>(retries = 2) {
                            callCount++
                            job?.cancel(CancellationException("Cancelled mid-call"))
                            throw CancellationException("Cancelled mid-call")
                        }
                    } catch (e: CancellationException) {
                        caughtCancellation = e
                        throw e
                    }
                }

            job.join()
            assertTrue(job.isCancelled)
            assertEquals("Cancelled mid-call", caughtCancellation?.message)
            assertEquals(1, callCount)
        }

    @Test
    fun cancellationDuringBackoffDelayPropagatesAndNeverTriggersNextAttempt() =
        runTest {
            var callCount = 0
            var job: Job? = null
            var caughtCancellation: CancellationException? = null

            job =
                launch {
                    try {
                        safeApiRead<String>(retries = 2) {
                            callCount++
                            if (callCount == 1) {
                                // Trigger cancel so the subsequent backoff delay in safeApiCall throws
                                job?.cancel(CancellationException("Cancelled before backoff ends"))
                                throw IOException("First attempt failed")
                            }
                            Response.success("never-reached")
                        }
                    } catch (e: CancellationException) {
                        caughtCancellation = e
                        throw e
                    }
                }

            job.join()
            assertTrue(job.isCancelled)
            assertEquals("Cancelled before backoff ends", caughtCancellation?.message)
            assertEquals(1, callCount)
        }

    // ── Success and Null/Unit Body Mapping Consistency ───────────────────────

    @Test
    fun safeApiReadMapsNonNullSuccessBodyCorrectly() =
        runTest {
            var callCount = 0
            val result =
                safeApiRead<String> {
                    callCount++
                    Response.success("payload")
                }
            assertTrue(result is NetworkResult.Success)
            assertEquals("payload", (result as NetworkResult.Success).data)
            assertEquals(1, callCount)
        }

    @Test
    fun safeApiReadMapsNullBodyCorrectly() =
        runTest {
            var callCount = 0
            val result =
                safeApiRead<String?> {
                    callCount++
                    Response.success(null)
                }
            assertTrue(result is NetworkResult.Success)
            assertNull((result as NetworkResult.Success).data)
            assertEquals(1, callCount)
        }

    @Test
    fun safeApiReadMapsUnitSuccessBodyCorrectly() =
        runTest {
            var callCount = 0
            val result =
                safeApiRead<Unit> {
                    callCount++
                    Response.success(Unit)
                }
            assertTrue(result is NetworkResult.Success)
            assertEquals(Unit, (result as NetworkResult.Success).data)
            assertEquals(1, callCount)
        }

    @Test
    fun safeApiCallMapsUnitSuccessBodyWithNullResponsePayload() =
        runTest {
            var callCount = 0
            val result =
                safeApiCall<Unit> {
                    callCount++
                    Response.success<Unit>(null)
                }
            assertTrue(result is NetworkResult.Success)
            // Unit is non-nullable: an empty 2xx body maps to the Unit value, never a smuggled null.
            assertEquals(Unit, (result as NetworkResult.Success).data)
            assertEquals(1, callCount)
        }
}
