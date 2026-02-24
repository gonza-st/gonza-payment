package com.gonza.payment.controller

import com.gonza.payment.dto.CreateUserRequest
import com.gonza.payment.dto.CreateUserResponse
import com.gonza.payment.dto.WalletResponse
import com.gonza.payment.service.UserService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api")
class UserController(private val userService: UserService) {

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    fun createUser(@RequestBody request: CreateUserRequest): CreateUserResponse {
        return userService.createUser(request)
    }

    @GetMapping("/users/{userId}/wallet")
    fun getWallet(@PathVariable userId: UUID): WalletResponse {
        return userService.getWallet(userId)
    }
}
