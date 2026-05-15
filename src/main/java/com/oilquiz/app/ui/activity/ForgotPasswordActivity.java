package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.oilquiz.app.R;
import com.oilquiz.app.viewmodel.UserViewModel;

public class ForgotPasswordActivity extends AppCompatActivity {

    private EditText emailEditText;
    private Button resetPasswordButton;
    private TextView loginTextView;
    private ProgressBar progressBar;

    private UserViewModel userViewModel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_forgot_password);

        initViews();
        setupListeners();
    }

    private void initViews() {
        emailEditText = findViewById(R.id.emailEditText);
        resetPasswordButton = findViewById(R.id.resetPasswordButton);
        loginTextView = findViewById(R.id.loginTextView);
        progressBar = findViewById(R.id.progressBar);

        userViewModel = new ViewModelProvider(this).get(UserViewModel.class);
    }

    private void setupListeners() {
        resetPasswordButton.setOnClickListener(v -> resetPassword());
        loginTextView.setOnClickListener(v -> navigateToLogin());
    }

    private void resetPassword() {
        String email = emailEditText.getText().toString().trim();

        if (validateInput(email)) {
            progressBar.setVisibility(View.VISIBLE);
            userViewModel.resetPassword(email, new UserViewModel.ResetPasswordCallback() {
                @Override
                public void onSuccess() {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(ForgotPasswordActivity.this, getString(R.string.reset_password_email_sent), Toast.LENGTH_SHORT).show();
                    navigateToLogin();
                }

                @Override
                public void onFailure(String error) {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(ForgotPasswordActivity.this, error, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private boolean validateInput(String email) {
        if (email.isEmpty()) {
            emailEditText.setError(getString(R.string.email_required));
            return false;
        }
        return true;
    }

    private void navigateToLogin() {
        Intent intent = new Intent(ForgotPasswordActivity.this, LoginActivity.class);
        startActivity(intent);
        finish();
    }
}