package com.oilquiz.app.viewmodel;

import android.app.Application;

import androidx.lifecycle.AndroidViewModel;

import com.oilquiz.app.model.User;
import com.oilquiz.app.repository.UserRepository;

public class UserViewModel extends AndroidViewModel {

    private UserRepository userRepository;

    public UserViewModel(Application application) {
        super(application);
        userRepository = new UserRepository(application);
    }

    public void login(String email, String password, LoginCallback callback) {
        userRepository.login(email, password, callback);
    }

    public void register(String email, String password, RegisterCallback callback) {
        userRepository.register(email, password, callback);
    }

    public void resetPassword(String email, ResetPasswordCallback callback) {
        userRepository.resetPassword(email, callback);
    }

    public interface LoginCallback {
        void onSuccess(User user);
        void onFailure(String error);
    }

    public interface RegisterCallback {
        void onSuccess();
        void onFailure(String error);
    }

    public interface ResetPasswordCallback {
        void onSuccess();
        void onFailure(String error);
    }
}