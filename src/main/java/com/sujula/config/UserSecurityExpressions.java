package com.sujula.config;

import com.sujula.model.user.User;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component("userSecurity")
public class UserSecurityExpressions {

    public boolean isSelf(Authentication authentication, Long id) {
        if (authentication == null || id == null) {
            return false;
        }
        return authentication.getPrincipal() instanceof User user
                && user.getId() != null
                && user.getId().equals(id);
    }
}
