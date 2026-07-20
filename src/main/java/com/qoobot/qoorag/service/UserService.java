package com.qoobot.qoorag.service;

import com.qoobot.qoorag.common.SecurityContext;
import com.qoobot.qoorag.entity.Role;
import com.qoobot.qoorag.entity.User;
import com.qoobot.qoorag.repository.RoleRepository;
import com.qoobot.qoorag.repository.UserRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 用户管理（4.4；仅系统管理员可操作） */
@Service
public class UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, RoleRepository roleRepository,
                       BCryptPasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /** 当前租户下未删除的用户（4.9 隔离） */
    public List<User> listUsers() {
        Long tenantId = SecurityContext.get().getTenantId();
        return userRepository.findAll().stream()
                .filter(u -> tenantId.equals(u.getTenantId()) && u.getDeletedAt() == null)
                .collect(Collectors.toList());
    }

    @Transactional
    public User createUser(String username, String password, String displayName, List<Long> roleIds) {
        if (userRepository.existsByUsername(username)) {
            throw new RuntimeException("用户名已存在");
        }
        Long tenantId = SecurityContext.get().getTenantId();
        User user = new User();
        user.setTenantId(tenantId);
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setDisplayName(displayName);
        user.setStatus("ACTIVE");
        user.setCreatedAt(LocalDateTime.now());
        Set<Role> roles = roleIds.stream()
                .map(id -> roleRepository.findById(id).orElseThrow(() -> new RuntimeException("角色不存在: " + id)))
                .collect(Collectors.toSet());
        user.setRoles(roles);
        return userRepository.save(user);
    }

    @Transactional
    public void assignRoles(Long userId, List<Long> roleIds) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        Set<Role> roles = roleIds.stream()
                .map(id -> roleRepository.findById(id).orElseThrow(() -> new RuntimeException("角色不存在: " + id)))
                .collect(Collectors.toSet());
        user.setRoles(roles);
        userRepository.save(user);
    }

    @Transactional
    public void setStatus(Long userId, String status) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        user.setStatus(status);
        userRepository.save(user);
    }
}
