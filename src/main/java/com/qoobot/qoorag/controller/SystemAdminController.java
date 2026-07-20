package com.qoobot.qoorag.controller;

import com.qoobot.qoorag.common.Result;
import com.qoobot.qoorag.common.SecurityContext;
import com.qoobot.qoorag.entity.Role;
import com.qoobot.qoorag.entity.User;
import com.qoobot.qoorag.service.AuditService;
import com.qoobot.qoorag.service.RoleService;
import com.qoobot.qoorag.service.UserService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 系统管理（仅系统管理员，4.4） */
@RestController
@RequestMapping("/api/admin")
public class SystemAdminController {

    private final UserService userService;
    private final RoleService roleService;
    private final AuditService auditService;

    public SystemAdminController(UserService userService, RoleService roleService, AuditService auditService) {
        this.userService = userService;
        this.roleService = roleService;
        this.auditService = auditService;
    }

    private void requireSysAdmin() {
        if (!SecurityContext.get().hasRole("系统管理员")) {
            throw new RuntimeException("无权限：需要系统管理员角色");
        }
    }

    @GetMapping("/users")
    public Result listUsers() {
        requireSysAdmin();
        List<User> users = userService.listUsers();
        auditService.log("LIST_USERS", "User", null, null, null);
        return Result.ok(users);
    }

    @PostMapping("/users")
    public Result createUser(@RequestBody Map<String, Object> body) {
        requireSysAdmin();
        String username = (String) body.get("username");
        String password = (String) body.get("password");
        String displayName = (String) body.get("displayName");
        @SuppressWarnings("unchecked")
        List<Long> roleIds = (List<Long>) body.get("roleIds");
        User user = userService.createUser(username, password, displayName, roleIds);
        auditService.log("CREATE_USER", "User", String.valueOf(user.getId()), null, username);
        return Result.ok(user);
    }

    @PostMapping("/users/{id}/roles")
    public Result assignRoles(@PathVariable Long id, @RequestBody Map<String, List<Long>> body) {
        requireSysAdmin();
        userService.assignRoles(id, body.get("roleIds"));
        auditService.log("ASSIGN_ROLES", "User", String.valueOf(id), null, body.get("roleIds").toString());
        return Result.ok();
    }

    @PutMapping("/users/{id}/status")
    public Result setStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        requireSysAdmin();
        userService.setStatus(id, body.get("status"));
        auditService.log("SET_USER_STATUS", "User", String.valueOf(id), null, body.get("status"));
        return Result.ok();
    }

    @GetMapping("/roles")
    public Result listRoles() {
        requireSysAdmin();
        List<Role> roles = roleService.listRoles();
        return Result.ok(roles);
    }

    @PostMapping("/roles")
    public Result createRole(@RequestBody Map<String, String> body) {
        requireSysAdmin();
        Role role = roleService.createRole(body.get("name"), body.get("description"));
        auditService.log("CREATE_ROLE", "Role", String.valueOf(role.getId()), null, body.get("name"));
        return Result.ok(role);
    }
}
