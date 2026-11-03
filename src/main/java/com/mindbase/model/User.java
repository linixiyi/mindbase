package com.mindbase.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(columnNames = "username"))
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(name = "org_tags")
    private String orgTags; // 用户所属组织标签，多个用逗号分隔

    @Column(name = "primary_org")
    private String primaryOrg; // 用户主组织标签

    /**
     * 是否启用（pm-012/G2）：users.enabled 列为禁用态真相源，1 启用、0 禁用，默认启用。
     * 旧实现的 Redis 持久键 user:status:disabled:{id} 已退役，不再作为禁用依据。
     */
    @Column(nullable = false, columnDefinition = "BOOLEAN DEFAULT 1")
    private Boolean enabled = true;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public enum Role {
        USER, ADMIN
    }
}
