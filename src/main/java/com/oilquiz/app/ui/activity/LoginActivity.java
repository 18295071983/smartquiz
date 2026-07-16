package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.button.MaterialButton;
import com.oilquiz.app.MainActivity;
import com.oilquiz.app.R;
import com.oilquiz.app.database.AppDatabase;
import com.oilquiz.app.database.UserDao;
import com.oilquiz.app.model.User;
import com.oilquiz.app.viewmodel.UserViewModel;

public class LoginActivity extends AppCompatActivity {

    private EditText emailEditText;
    private EditText passwordEditText;
    private MaterialButton loginButton;
    private MaterialButton registerButton;
    private MaterialButton forgotPasswordButton;
    private ProgressBar progressBar;

    private UserViewModel userViewModel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        // 检查是否已存在登录用户
        checkLoggedInUser();

        initViews();
        setupListeners();
    }

    private void checkLoggedInUser() {
        new Thread(() -> {
            try {
                AppDatabase database = AppDatabase.getDatabase(this);
                if (database == null) {
                    System.out.println("数据库初始化失败，无法检查登录状态");
                    return;
                }
                UserDao userDao = database.userDao();
                User loggedInUser = userDao.getLoggedInUser();
                
                if (loggedInUser != null) {
                    // 已存在登录用户，直接跳转到主页面
                    runOnUiThread(() -> navigateToMain());
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void initViews() {
        emailEditText = findViewById(R.id.emailEditText);
        passwordEditText = findViewById(R.id.passwordEditText);
        loginButton = findViewById(R.id.loginButton);
        registerButton = findViewById(R.id.registerButton);
        forgotPasswordButton = findViewById(R.id.forgotPasswordButton);
        progressBar = findViewById(R.id.progressBar);

        userViewModel = new ViewModelProvider(this).get(UserViewModel.class);
    }

    private void setupListeners() {
        loginButton.setOnClickListener(v -> login());
        registerButton.setOnClickListener(v -> navigateToRegister());
        forgotPasswordButton.setOnClickListener(v -> navigateToForgotPassword());
    }

    private void login() {
        String email = emailEditText.getText().toString().trim();
        String password = passwordEditText.getText().toString().trim();

        if (validateInputs(email, password)) {
            progressBar.setVisibility(View.VISIBLE);
            userViewModel.login(email, password, new UserViewModel.LoginCallback() {
                @Override
                public void onSuccess(User user) {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(LoginActivity.this, getString(R.string.login_success), Toast.LENGTH_SHORT).show();
                    navigateToMain();
                }

                @Override
                public void onFailure(String error) {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(LoginActivity.this, error, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private boolean validateInputs(String email, String password) {
        if (email.isEmpty()) {
            emailEditText.setError(getString(R.string.email_required));
            return false;
        }
        if (password.isEmpty()) {
            passwordEditText.setError(getString(R.string.password_required));
            return false;
        }
        return true;
    }

    private void navigateToRegister() {
        Intent intent = new Intent(LoginActivity.this, RegisterActivity.class);
        startActivity(intent);
    }

    private void navigateToForgotPassword() {
        Intent intent = new Intent(LoginActivity.this, ForgotPasswordActivity.class);
        startActivity(intent);
    }

    private void navigateToMain() {
        Intent intent = new Intent(LoginActivity.this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}