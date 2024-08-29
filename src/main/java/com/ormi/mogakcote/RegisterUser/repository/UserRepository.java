package com.ormi.mogakcote.RegisterUser.repository;

import com.ormi.mogakcote.RegisterUser.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
    boolean existsByNickname(String nickname);

    boolean existsByEmail(String email);
}