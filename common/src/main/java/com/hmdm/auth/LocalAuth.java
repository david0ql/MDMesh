package com.hmdm.auth;

import com.google.inject.Inject;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.UserDAO;
import com.hmdm.persistence.domain.User;
import com.hmdm.util.PasswordUtil;

import javax.inject.Named;
import javax.inject.Singleton;

@Singleton
public class LocalAuth implements HmdmAuthInterface {

    private UnsecureDAO userDAO;

    @Inject
    public LocalAuth(UnsecureDAO userDAO) {
        this.userDAO = userDAO;
    }

    @Override
    public User findUser(String login) {
        return userDAO.findByLoginOrEmail(login);
    }

    @Override
    public boolean authenticate(User user, String password) {
        boolean match = PasswordUtil.passwordMatch(password, user.getPassword());
        if (!match) {
            userDAO.setUserLoginFailTime(user, System.currentTimeMillis());
        } else if (PasswordUtil.isLegacyHash(user.getPassword())) {
            // Upgrade the inherited SHA-1 hash now that we hold the password (only if it is still the one we checked).
            String upgraded = PasswordUtil.getHashFromMd5(password);
            if (userDAO.upgradePasswordHash(user.getId(), user.getPassword(), upgraded)) {
                user.setPassword(upgraded);
            }
        }
        return match;
    }
}
