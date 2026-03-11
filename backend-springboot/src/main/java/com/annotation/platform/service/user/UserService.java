package com.annotation.platform.service.user;

import com.annotation.platform.dto.response.common.PageableResponse;
import com.annotation.platform.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface UserService {

    User findById(Long id);

    User findByUsername(String username);

    User findByEmail(String email);

    User createUser(User user);

    User updateUser(Long userId, User user);

    void deleteUser(Long userId);

    void updateLastLogin(Long userId);

    List<User> findByOrganizationId(Long organizationId);

    Page<User> findByOrganizationId(Long organizationId, Pageable pageable);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    PageableResponse buildPageableResponse(Page<?> page);
}
