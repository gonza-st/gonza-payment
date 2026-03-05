package com.gonza.payment.exception

import org.springframework.http.HttpStatus

open class ApiException(
    val status: HttpStatus,
    override val message: String
) : RuntimeException(message)

class NotFoundException(message: String) : ApiException(HttpStatus.NOT_FOUND, message)

class ConflictException(message: String) : ApiException(HttpStatus.CONFLICT, message)

class InsufficientBalanceException : ApiException(HttpStatus.BAD_REQUEST, "Insufficient balance")

class PgPaymentFailedException(errorCode: String) :
    ApiException(HttpStatus.BAD_GATEWAY, "PG payment failed: $errorCode")

class UnprocessableException(message: String) : ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message)

class AlreadyConsumedException : ApiException(HttpStatus.CONFLICT, "Gifticon already consumed")
