package com.oilquiz.app.manager;

import com.oilquiz.app.data.dao.UserDao;
import com.oilquiz.app.model.User;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;

@RunWith(AndroidJUnit4.class)
public class UserManagerTest {

    @Mock
    private UserDao mockUserDao;

    private UserManager userManager;
    private User testUser;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        userManager = UserManager.getInstance(mockUserDao);
        
        testUser = new User();
        testUser.setId("test-user-001");
        testUser.setUsername("testuser");
        testUser.setEmail("test@example.com");
        testUser.setPassword("hashedPassword123");
        testUser.setCreatedAt(System.currentTimeMillis());
    }

    @Test
    public void testLogin_Success() {
        when(mockUserDao.getByUsername("testuser")).thenReturn(testUser);
        
        boolean result = userManager.login("testuser", "password123");
        
        assertTrue("Login should succeed", result);
        assertTrue("User should be logged in", userManager.isLoggedIn());
    }

    @Test
    public void testLogin_InvalidPassword() {
        when(mockUserDao.getByUsername("testuser")).thenReturn(testUser);
        
        boolean result = userManager.login("testuser", "wrongpassword");
        
        assertFalse("Login should fail with wrong password", result);
        assertFalse("User should not be logged in", userManager.isLoggedIn());
    }

    @Test
    public void testLogin_NonExistentUser() {
        when(mockUserDao.getByUsername("nonexistent")).thenReturn(null);
        
        boolean result = userManager.login("nonexistent", "password");
        
        assertFalse("Login should fail for non-existent user", result);
    }

    @Test
    public void testLogout() {
        when(mockUserDao.getByUsername("testuser")).thenReturn(testUser);
        userManager.login("testuser", "password123");
        
        userManager.logout();
        
        assertFalse("User should be logged out after logout", 
                userManager.isLoggedIn());
        assertNull("Current user should be null after logout",
                userManager.getCurrentUser());
    }

    @Test
    public void testGetCurrentUser_WhenLoggedIn() {
        when(mockUserDao.getByUsername("testuser")).thenReturn(testUser);
        userManager.login("testuser", "password123");
        
        User currentUser = userManager.getCurrentUser();
        
        assertNotNull("Current user should not be null", currentUser);
        assertEquals("Username should match", "testuser", currentUser.getUsername());
    }

    @Test
    public void testGetCurrentUser_WhenNotLoggedIn() {
        User currentUser = userManager.getCurrentUser();
        
        assertNull("Current user should be null when not logged in", 
                currentUser);
    }

    @Test
    public void testRegister_NewUser() {
        when(mockUserDao.getByUsername("newuser")).thenReturn(null);
        when(mockUserDao.insert(any(User.class))).thenReturn(1L);
        
        boolean result = userManager.register("newuser", "new@example.com", "pass123");
        
        assertTrue("Registration should succeed for new user", result);
        verify(mockUserDao, times(1)).insert(any(User.class));
    }

    @Test
    public void testRegister_DuplicateUser() {
        when(mockUserDao.getByUsername("existinguser")).thenReturn(testUser);
        
        boolean result = userManager.register("existinguser", "email@test.com", "pass123");
        
        assertFalse("Registration should fail for duplicate username", result);
    }

    @Test
    public void testUpdateUserProfile() {
        when(mockUserDao.getByUsername("testuser")).thenReturn(testUser);
        userManager.login("testuser", "password123");
        
        testUser.setUsername("updateduser");
        testUser.setEmail("updated@example.com");
        
        userManager.updateUser(testUser);
        
        verify(mockUserDao, times(1)).update(testUser);
    }

    @Test
    public void testChangePassword_Success() {
        when(mockUserDao.getByUsername("testuser")).thenReturn(testUser);
        userManager.login("testuser", "password123");
        
        boolean result = userManager.changePassword("password123", "newpassword456");
        
        assertTrue("Password change should succeed", result);
        verify(mockUserDao, times(1)).update(testUser);
    }

    @Test
    public void testChangePassword_WrongOldPassword() {
        when(mockUserDao.getByUsername("testuser")).thenReturn(testUser);
        userManager.login("testuser", "password123");
        
        boolean result = userManager.changePassword("wrongold", "newpassword456");
        
        assertFalse("Password change should fail with wrong old password", result);
    }
}
