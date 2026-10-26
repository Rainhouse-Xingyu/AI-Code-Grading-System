package com.rainexis.backend.service.business;

import com.rainexis.backend.mapper.TUserMapper;
import com.rainexis.backend.security.AuthUser;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class TeacherClassService {
    private final TUserMapper userMapper;
    private static final Pattern RANGE = Pattern.compile("^(.*?)(\\d+)\\s*[-~～至]\\s*(?:\\1)?(\\d+)$");
    public TeacherClassService(TUserMapper userMapper) { this.userMapper = userMapper; }

    public List<String> managedClasses(AuthUser user) {
        if (user == null || user.isStudent()) return List.of();
        var record = userMapper.selectById(user.id());
        if (record == null) return List.of();
        // 班级权限以数据库中的最新分配为准，不使用 JWT 中的旧班级配置。
        String configured = record.getTeachingClass();
        if (configured == null || configured.isBlank()) configured = record.getClassName();
        return parse(configured);
    }

    public List<String> parse(String text) {
        if (text == null || text.isBlank()) return List.of();
        var result = new LinkedHashSet<String>();
        for (String token : text.split("[,，、;；\\n]+")) {
            String value = token.trim();
            if (value.isEmpty()) continue;
            var match = RANGE.matcher(value);
            if (match.matches()) {
                try {
                    int start = Integer.parseInt(match.group(2));
                    int end = Integer.parseInt(match.group(3));
                    if (end >= start && end - start <= 200) {
                        for (int number = start; number <= end; number++) result.add(match.group(1) + String.format("%0" + match.group(2).length() + "d", number));
                        continue;
                    }
                } catch (NumberFormatException ignored) { }
            }
            result.add(value);
        }
        return new ArrayList<>(result);
    }

    public boolean manages(AuthUser user, String className) { return managedClasses(user).contains(className); }
}
