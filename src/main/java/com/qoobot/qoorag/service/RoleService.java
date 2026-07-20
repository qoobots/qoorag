package com.qoobot.qoorag.service;

import com.qoobot.qoorag.entity.Role;
import com.qoobot.qoorag.repository.RoleRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/** 角色管理（4.4） */
@Service
public class RoleService {

    private final RoleRepository roleRepository;

    public RoleService(RoleRepository roleRepository) {
        this.roleRepository = roleRepository;
    }

    public List<Role> listRoles() {
        return roleRepository.findAll();
    }

    public Role createRole(String name, String description) {
        if (roleRepository.findByName(name).isPresent()) {
            throw new RuntimeException("角色名已存在");
        }
        Role role = new Role();
        role.setName(name);
        role.setDescription(description);
        return roleRepository.save(role);
    }
}
