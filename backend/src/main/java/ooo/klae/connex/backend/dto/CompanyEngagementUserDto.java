package ooo.klae.connex.backend.dto;

/** One recently involved workspace member plus the total distinct member count. */
public record CompanyEngagementUserDto(int userId, long totalUsers) {}
