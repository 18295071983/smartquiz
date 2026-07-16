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

import com.oilquiz.app.R;
import com.oilquiz.app.viewmodel.UserViewModel;

public class RegisterActivity extends AppCompatActivity {

    private EditText emailEditText;
    private EditText passwordEditText;
    private EditText confirmPasswordEditText;
    private Button registerButton;
    private TextView loginTextView;
    private ProgressBar progressBar;

    private UserViewModel userViewModel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        initViews();
        setupListeners();
    }

    private void initViews() {
        emailEditText = findViewById(R.id.editTextEmail);
        passwordEditText = findViewById(R.id.editTextPassword);
        confirmPasswordEditText = findViewById(R.id.confirmPasswordEditText);
        registerButton = findViewById(R.id.registerButton);
        loginTextView = findViewById(R.id.textViewLogin);
        progressBar = findViewById(R.id.progressBar);

        userViewModel = new UserViewModel(getApplication());
    }

    private void setupListeners() {
        registerButton.setOnClickListener(v -> register());
        loginTextView.setOnClickListener(v -> navigateToLogin());
    }

    private void register() {
        String email = emailEditText.getText().toString().trim();
        String password = passwordEditText.getText().toString().trim();
        String confirmPassword = confirmPasswordEditText.getText().toString().trim();

        if (validateInputs(email, password, confirmPassword)) {
            progressBar.setVisibility(View.VISIBLE);
            userViewModel.register(email, password, new UserViewModel.RegisterCallback() {
                @Override
                public void onSuccess() {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(RegisterActivity.this, getString(R.string.register_success), Toast.LENGTH_SHORT).show();
                    navigateToLogin();
                }

                @Override
                public void onFailure(String error) {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(RegisterActivity.this, error, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private boolean validateInputs(String email, String password, String confirmPassword) {
        if (email.isEmpty()) {
            emailEditText.setError(getString(R.string.email_required));
            return false;
        }
        if (password.isEmpty()) {
            passwordEditText.setError(getString(R.string.password_required));
            return false;
        }
        if (password.length() < 6) {
            passwordEditText.setError(getString(R.string.password_too_short));
            return false;
        }
        if (!password.equals(confirmPassword)) {
            confirmPasswordEditText.setError(getString(R.string.passwords_not_match));
            return false;
        }
        return true;
    }

    private void navigateToLogin() {
        Intent intent = new Intent(RegisterActivity.this, LoginActivity.class);
        startActivity(intent);
        finish();
    }
}