package com.oilquiz.app.repository;

import android.app.Application;

import com.oilquiz.app.database.AppDatabase;
import com.oilquiz.app.database.UserDao;
import com.oilquiz.app.model.User;
import com.oilquiz.app.viewmodel.UserViewModel;

public class UserRepository {

    private UserDao userDao;

    public UserRepository(Application application) {
        AppDatabase database = AppDatabase.getDatabase(application);
        if (database != null) {
            userDao = database.userDao();
        }
    }

    public void login(String email, String password, UserViewModel.LoginCallback callback) {
        new Thread(() -> {
            try {
                if (userDao == null) {
                    callback.onFailure("Database not initialized");
                    return;
                }
                // 从数据库中查询用户
                User user = userDao.login(email, password);
                
                if (user != null) {
                    // 登录成功，更新登录状态
                    user.setIsLoggedIn(1);
                    userDao.update(user);
                    callback.onSuccess(user);
                } else {
                    callback.onFailure("Invalid email or password");
                }
            } catch (Exception e) {
                callback.onFailure("Login failed: " + e.getMessage());
            }
        }).start();
    }

    public void register(String email, String password, UserViewModel.RegisterCallback callback) {
        new Thread(() -> {
            try {
                if (userDao == null) {
                    callback.onFailure("Database not initialized");
                    return;
                }
                // 检查邮箱是否已存在
                User existingUser = userDao.getByEmail(email);
                if (existingUser != null) {
                    callback.onFailure("Email already exists");
                    return;
                }
                
                // 创建新用户
                User user = new User();
                user.setEmail(email);
                user.setPassword(password);
                user.setIsLoggedIn(1);
                
                // 登出所有其他用户
                userDao.logoutAll();
                
                // 插入新用户
                long userId = userDao.insert(user);
                user.setId(userId);
                
                callback.onSuccess();
            } catch (Exception e) {
                callback.onFailure("Registration failed: " + e.getMessage());
            }
        }).start();
    }

    public void resetPassword(String email, UserViewModel.ResetPasswordCallback callback) {
        new Thread(() -> {
            try {
                if (userDao == null) {
                    callback.onFailure("Database not initialized");
                    return;
                }
                // 检查邮箱是否存在
                User user = userDao.getByEmail(email);
                if (user == null) {
                    callback.onFailure("Email not found");
                    return;
                }
                
                // 这里应该发送密码重置邮件
                // 实际应用中，应该生成一个重置令牌并发送到用户邮箱
                
                callback.onSuccess();
            } catch (Exception e) {
                callback.onFailure("Reset password failed: " + e.getMessage());
            }
        }).start();
    }

    public User getLoggedInUser() {
        if (userDao == null) {
            return null;
        }
        return userDao.getLoggedInUser();
    }

    public void logout() {
        if (userDao != null) {
            userDao.logoutAll();
        }
    }
}