package com.mingjin.school_wechat.controller;

import com.mingjin.school_wechat.common.api.ApiResponse;
import com.mingjin.school_wechat.model.request.ChangePasswordRequest;
import com.mingjin.school_wechat.model.request.LoginRequest;
import com.mingjin.school_wechat.model.request.RegisterRequest;
import com.mingjin.school_wechat.model.request.UpdateProfileRequest;
import com.mingjin.school_wechat.model.view.LoginResponse;
import com.mingjin.school_wechat.model.view.UserProfileView;
import com.mingjin.school_wechat.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/auth/login")
    public ApiResponse<LoginResponse> login(@RequestBody LoginRequest request, HttpServletRequest httpServletRequest) {
        return ApiResponse.success("登录成功", authService.login(request, httpServletRequest.getRemoteAddr()));
    }

    @PostMapping("/auth/register")
    public ApiResponse<LoginResponse> register(@RequestBody RegisterRequest request, HttpServletRequest httpServletRequest) {
        return ApiResponse.success("注册成功", authService.register(request, httpServletRequest.getRemoteAddr()));
    }

    @PostMapping("/auth/logout")
    public ApiResponse<Void> logout() {
        authService.logout();
        return ApiResponse.success("退出登录成功", null);
    }

    @PostMapping("/auth/password/change")
    public ApiResponse<Void> changePassword(@RequestBody ChangePasswordRequest request) {
        authService.changePassword(request);
        return ApiResponse.success("密码修改成功", null);
    }

    @GetMapping("/users/me")
    public ApiResponse<UserProfileView> me() {
        return ApiResponse.success(authService.getCurrentUser());
    }

    @PostMapping("/users/me/update")
    public ApiResponse<UserProfileView> updateProfile(@RequestBody UpdateProfileRequest request) {
        return ApiResponse.success("资料更新成功", authService.updateProfile(request));
    }
}
