package com.oilquiz.app.ui.activity;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import com.oilquiz.app.R;
import com.oilquiz.app.viewmodel.UserViewModel;

public class UserActivity extends AppCompatActivity {

    private EditText usernameEditText;
    private EditText emailEditText;
    private EditText passwordEditText;
    private Button loginButton;
    private Button registerButton;
    private UserViewModel userViewModel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_user);

        initViews();
        setupViewModel();
        setupListeners();
    }

    private void initViews() {
        usernameEditText = findViewById(R.id.et_username);
        emailEditText = findViewById(R.id.et_email);
        passwordEditText = findViewById(R.id.et_password);
        loginButton = findViewById(R.id.btn_login);
        registerButton = findViewById(R.id.btn_register);
    }

    private void setupViewModel() {
        userViewModel = new ViewModelProvider(this).get(UserViewModel.class);
    }

    private void setupListeners() {
        loginButton.setOnClickListener(v -> login());
        registerButton.setOnClickListener(v -> navigateToRegister());
    }

    private void login() {
        String email = emailEditText.getText().toString().trim();
        String password = passwordEditText.getText().toString().trim();

        if (validateInputs(email, password)) {
            userViewModel.login(email, password, new UserViewModel.LoginCallback() {
                @Override
                public void onSuccess(com.oilquiz.app.model.User user) {
                    Toast.makeText(UserActivity.this, "登录成功", Toast.LENGTH_SHORT).show();
                    finish();
                }

                @Override
                public void onFailure(String error) {
                    Toast.makeText(UserActivity.this, "登录失败：" + error, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private boolean validateInputs(String email, String password) {
        if (email.isEmpty()) {
            emailEditText.setError("请输入邮箱");
            return false;
        }
        if (password.isEmpty()) {
            passwordEditText.setError("请输入密码");
            return false;
        }
        return true;
    }

    private void navigateToRegister() {
        Intent intent = new Intent(this, RegisterActivity.class);
        startActivity(intent);
    }
}
