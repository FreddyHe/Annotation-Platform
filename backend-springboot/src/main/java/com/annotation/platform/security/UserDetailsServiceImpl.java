package com.annotation.platform.security;

import com.annotation.platform.entity.User;
import com.annotation.platform.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user;
        
        // 支持使用邮箱或用户名登录
        if (username.contains("@")) {
            // 如果包含@符号，当作邮箱处理
            user = userRepository.findByEmail(username)
                    .filter(User::getIsActive)
                    .orElseThrow(() -> new UsernameNotFoundException("用户不存在或未激活: " + username));
        } else {
            // 否则当作用户名处理
            user = userRepository.findByUsernameAndIsActiveTrue(username)
                    .orElseThrow(() -> new UsernameNotFoundException("用户不存在或未激活: " + username));
        }

        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getUsername())
                .password(user.getPasswordHash())
                .authorities(Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")))
                .accountLocked(!user.getIsActive())
                .build();
    }
}
