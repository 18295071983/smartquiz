package com.oilquiz.app.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.oilquiz.app.model.User;

@Dao
public interface UserDao {
    @Insert
    long insert(User user);

    @Update
    void update(User user);

    @Query("DELETE FROM user WHERE id = :id")
    void deleteUser(long id);

    @Query("SELECT * FROM user WHERE id = :id")
    User getById(long id);

    @Query("SELECT * FROM user WHERE username = :username")
    User getByUsername(String username);

    @Query("SELECT * FROM user WHERE email = :email")
    User getByEmail(String email);

    @Query("SELECT * FROM user WHERE email = :email AND password = :password")
    User login(String email, String password);

    @Query("SELECT * FROM user WHERE isLoggedIn = 1")
    User getLoggedInUser();

    @Query("UPDATE user SET isLoggedIn = 0")
    void logoutAll();

    @Query("SELECT * FROM user")
    java.util.List<User> getAllUsers();
}
