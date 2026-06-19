package com.m57.hermescontrol.data.remote

import retrofit2.Response
import java.io.IOException

sealed interface NetworkResult<out T> {
    data class Success<out T>(
        val data: T,
    ) : NetworkResult<T>

    data class Failure(
        val error: NetworkError,
    ) : NetworkResult<Nothing>
}

sealed interface NetworkError {
    val message: String

    data class Http(
        val code: Int,
        override val message: String,
    ) : NetworkError

    data class Connection(
        override val message: String,
        val cause: IOException,
    ) : NetworkError

    data class Unknown(
        override val message: String,
        val cause: Throwable,
    ) : NetworkError
}

fun mapHttpError(code: Int): NetworkError {
    val message =
        when (code) {
            400 -> "Bad Request (HTTP 400): The server could not understand the request."
            401 -> "Unauthorized (HTTP 401): Authentication token is invalid or expired."
            403 -> "Forbidden (HTTP 403): You do not have permission to access this resource."
            404 -> "Not Found (HTTP 404): The requested resource could not be found."
            405 -> "Method Not Allowed (HTTP 405)."
            408 -> "Request Timeout (HTTP 408): The server timed out waiting for the request."
            409 -> "Conflict (HTTP 409): The request conflicts with current server state."
            429 -> "Too Many Requests (HTTP 429): Rate limit exceeded."
            in 500..599 -> "Server Error (HTTP $code): The server encountered an error."
            else -> "HTTP Error $code: Unexpected server response."
        }
    return NetworkError.Http(code, message)
}

suspend inline fun <reified T> safeApiCall(
    retries: Int = 3,
    crossinline call: suspend () -> Response<T>,
): NetworkResult<T> {
    var lastException: IOException? = null
    for (attempt in 0..retries) {
        try {
            val response = call()
            if (response.isSuccessful) {
                val body = response.body()
                return if (body != null) {
                    NetworkResult.Success(body)
                } else {
                    @Suppress("UNCHECKED_CAST")
                    NetworkResult.Success(null as T)
                }
            } else {
                val code = response.code()
                if (code in 500..599 && attempt < retries) {
                    continue
                }
                return NetworkResult.Failure(mapHttpError(code))
            }
        } catch (e: IOException) {
            lastException = e
            if (attempt == retries) {
                return NetworkResult.Failure(
                    NetworkError.Connection(
                        "Network connection failure: ${e.message ?: "Unknown connection error"}",
                        e,
                    ),
                )
            }
        } catch (e: Exception) {
            return NetworkResult.Failure(
                NetworkError.Unknown(
                    "An unexpected error occurred: ${e.localizedMessage ?: e.message}",
                    e,
                ),
            )
        }
    }
    return NetworkResult.Failure(
        NetworkError.Connection(
            "Network connection failure after $retries attempts: ${lastException?.message}",
            lastException ?: IOException("Unknown connection error"),
        ),
    )
}
