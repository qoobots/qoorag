package com.qoobot.qoorag.service;

import com.qoobot.qoorag.entity.Role;
import com.qoobot.qoorag.entity.Tenant;
import com.qoobot.qoorag.entity.User;
import com.qoobot.qoorag.repository.RoleRepository;
import com.qoobot.qoorag.repository.TenantRepository;
import com.qoobot.qoorag.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** 启动种子数据：默认管理员账号 + 两角色（4.4） */
@Service
public class SeedService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final TenantRepository tenantRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    @Value("${qoorag.security.default-admin-password:123456}")
    private String defaultAdminPassword;

    public SeedService(UserRepository userRepository, RoleRepository roleRepository,
                       TenantRepository tenantRepository, BCryptPasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.tenantRepository = tenantRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @PostConstruct
    @Transactional
    public void seed() {
        // 确保默认租户存在
        Tenant tenant = tenantRepository.findById(1L).orElseGet(() -> {
            Tenant t = new Tenant();
            t.setId(1L);
            t.setName("DEFAULT_TENANT");
            return tenantRepository.save(t);
        });

        // 确保两角色存在
        Role sysAdmin = roleRepository.findByName("系统管理员").orElseGet(() -> {
            Role r = new Role();
            r.setName("系统管理员");
            r.setDescription("平台级治理：用户/角色/资源池");
            return roleRepository.save(r);
        });
        Role kbAdmin = roleRepository.findByName("知识管理员").orElseGet(() -> {
            Role r = new Role();
            r.setName("知识管理员");
            r.setDescription("知识库与检索链路运营");
            return roleRepository.save(r);
        });

        // 创建默认 admin 账号（若不存在）
        Optional<User> existing = userRepository.findByUsername("admin");
        if (existing.isEmpty()) {
            User admin = new User();
            admin.setTenantId(tenant.getId());
            admin.setUsername("admin");
            admin.setPassword(passwordEncoder.encode(defaultAdminPassword));
            admin.setDisplayName("Administrator");
            admin.setStatus("ACTIVE");
            Set<Role> roles = new HashSet<>();
            roles.add(sysAdmin);
            roles.add(kbAdmin);
            admin.setRoles(roles);
            userRepository.save(admin);
        }
    }
}
