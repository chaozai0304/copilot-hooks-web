package com.copilot.hooks.controller;

import com.copilot.hooks.domain.User;
import com.copilot.hooks.repository.UserRepository;
import com.copilot.hooks.security.AppPrincipal;
import com.copilot.hooks.security.TokenService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final UserRepository userRepo;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;

    public record CreateUserRequest(@NotBlank String username, String displayName, String email,
                                    @NotBlank String password, String role) {}

    @GetMapping("/users")
    public List<Map<String, Object>> list(@AuthenticationPrincipal AppPrincipal me) {
        return userRepo.findAll().stream().map(this::toView).toList();
    }

    @PostMapping("/users")
    public ResponseEntity<?> create(@RequestBody CreateUserRequest req) {
        if (userRepo.existsByUsername(req.username())) {
            return ResponseEntity.status(409).body(Map.of("error", "username exists"));
        }
        User u = new User();
        u.setUsername(req.username());
        u.setDisplayName(req.displayName());
        u.setEmail(req.email());
        u.setRole(req.role() == null || req.role().isBlank() ? "USER" : req.role().toUpperCase());
        u.setPasswordHash(passwordEncoder.encode(req.password()));
        return ResponseEntity.ok(toView(userRepo.save(u)));
    }

    @GetMapping("/users/import/template")
    public ResponseEntity<byte[]> downloadImportTemplate() {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("users");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("username");
            header.createCell(1).setCellValue("displayName");
            header.createCell(2).setCellValue("email");
            header.createCell(3).setCellValue("password");
            header.createCell(4).setCellValue("role");
            header.createCell(5).setCellValue("enabled");

            Row sample = sheet.createRow(1);
            sample.createCell(0).setCellValue("alice");
            sample.createCell(1).setCellValue("Alice");
            sample.createCell(2).setCellValue("alice@example.com");
            sample.createCell(3).setCellValue("ChangeMe123");
            sample.createCell(4).setCellValue("USER");
            sample.createCell(5).setCellValue("true");

            Row note = sheet.createRow(3);
            note.createCell(0).setCellValue("说明：username/password 必填；role 可填 USER/ADMIN；enabled 可填 true/false/启用/禁用");

            for (int i = 0; i <= 5; i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(out);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=user-import-template.xlsx")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/users/import")
    public Map<String, Object> importExcel(@RequestParam("file") MultipartFile file) {
        DataFormatter formatter = new DataFormatter();
        List<Map<String, Object>> errors = new ArrayList<>();
        int created = 0;
        int skipped = 0;
        try (InputStream inputStream = file.getInputStream(); Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = workbook.getNumberOfSheets() > 0 ? workbook.getSheetAt(0) : null;
            if (sheet == null) return Map.of("created", 0, "skipped", 0, "errors", List.of(Map.of("row", 0, "message", "Excel 中没有可读取的 Sheet")));
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow == null) return Map.of("created", 0, "skipped", 0, "errors", List.of(Map.of("row", 0, "message", "缺少表头行")));

            Map<String, Integer> headers = new HashMap<>();
            for (Cell cell : headerRow) {
                headers.put(normalizeHeader(formatter.formatCellValue(cell)), cell.getColumnIndex());
            }
            for (int i = headerRow.getRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || isBlankRow(row, formatter)) continue;
                try {
                    String username = cell(row, headers, formatter, "username", "用户名", "账号", "login");
                    String password = cell(row, headers, formatter, "password", "密码", "初始密码");
                    String displayName = cell(row, headers, formatter, "displayname", "display_name", "名称", "显示名", "姓名");
                    String email = cell(row, headers, formatter, "email", "邮箱", "mail");
                    String role = cell(row, headers, formatter, "role", "角色");
                    String enabled = cell(row, headers, formatter, "enabled", "启用", "状态");
                    if (username == null || username.isBlank()) {
                        errors.add(Map.of("row", i + 1, "message", "缺少用户名"));
                        skipped++;
                        continue;
                    }
                    if (password == null || password.isBlank()) {
                        errors.add(Map.of("row", i + 1, "message", "缺少密码"));
                        skipped++;
                        continue;
                    }
                    if (userRepo.existsByUsername(username)) {
                        skipped++;
                        continue;
                    }
                    User u = new User();
                    u.setUsername(username.trim());
                    u.setDisplayName(blankToNull(displayName));
                    u.setEmail(blankToNull(email));
                    u.setRole(role == null || role.isBlank() ? "USER" : role.trim().toUpperCase());
                    u.setEnabled(parseEnabled(enabled));
                    u.setPasswordHash(passwordEncoder.encode(password));
                    userRepo.save(u);
                    created++;
                } catch (Exception e) {
                    errors.add(Map.of("row", i + 1, "message", e.getMessage() == null ? String.valueOf(e) : e.getMessage()));
                    skipped++;
                }
            }
        } catch (Exception e) {
            return Map.of("created", created, "skipped", skipped, "errors", List.of(Map.of("row", 0, "message", "导入失败：" + e.getMessage())));
        }
        return Map.of("created", created, "skipped", skipped, "errors", errors);
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        userRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/{id}/disable")
    public ResponseEntity<?> disable(@PathVariable Long id) {
        return userRepo.findById(id).map(u -> { u.setEnabled(false); userRepo.save(u); return ResponseEntity.ok(toView(u)); })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/users/{id}/enable")
    public ResponseEntity<?> enable(@PathVariable Long id) {
        return userRepo.findById(id).map(u -> { u.setEnabled(true); userRepo.save(u); return ResponseEntity.ok(toView(u)); })
                .orElse(ResponseEntity.notFound().build());
    }

    public record AdminTokenReq(@NotBlank String name, OffsetDateTime expiresAt) {}

    @PostMapping("/users/{id}/tokens")
    public Map<String, Object> issueForUser(@PathVariable Long id, @RequestBody AdminTokenReq req) {
        TokenService.CreatedToken t = tokenService.createToken(id, req.name(), req.expiresAt());
        return Map.of("token", t.plaintext(),
                "id", t.token().getId(),
                "prefix", t.token().getTokenPrefix(),
                "name", t.token().getName(),
                "expiresAt", t.token().getExpiresAt());
    }

    private Map<String, Object> toView(User u) {
        return Map.of(
                "id", u.getId(),
                "username", u.getUsername(),
                "displayName", u.getDisplayName() == null ? "" : u.getDisplayName(),
                "email", u.getEmail() == null ? "" : u.getEmail(),
                "role", u.getRole(),
                "enabled", u.isEnabled(),
                "createdAt", u.getCreatedAt()
        );
    }

    private static String normalizeHeader(String value) {
        return value == null ? "" : value.trim().toLowerCase().replace(" ", "").replace("_", "").replace("-", "");
    }

    private static String cell(Row row, Map<String, Integer> headers, DataFormatter formatter, String... aliases) {
        for (String alias : aliases) {
            Integer idx = headers.get(normalizeHeader(alias));
            if (idx == null) continue;
            Cell cell = row.getCell(idx);
            String value = cell == null ? null : formatter.formatCellValue(cell);
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

    private static boolean isBlankRow(Row row, DataFormatter formatter) {
        for (Cell cell : row) {
            if (!formatter.formatCellValue(cell).isBlank()) return false;
        }
        return true;
    }

    private static boolean parseEnabled(String value) {
        if (value == null || value.isBlank()) return true;
        String normalized = value.trim().toLowerCase();
        return List.of("1", "true", "yes", "y", "enabled", "enable", "启用", "是").contains(normalized);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
