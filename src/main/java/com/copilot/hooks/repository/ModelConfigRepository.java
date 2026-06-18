package com.copilot.hooks.repository;

import com.copilot.hooks.domain.ModelConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ModelConfigRepository extends JpaRepository<ModelConfig, Long> {
    List<ModelConfig> findByEnabledTrueOrderByUpdatedAtDesc();
    List<ModelConfig> findAllByOrderByUpdatedAtDesc();
    Optional<ModelConfig> findFirstByEnabledTrueAndDefaultConfigTrueOrderByUpdatedAtDesc();
}
