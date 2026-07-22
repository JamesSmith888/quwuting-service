package org.quwuting.quwutingservice.auth.dto.response;

import org.quwuting.quwutingservice.user.dto.response.UserInfoResponse;

public record LoginResponse(
        String token,
        UserInfoResponse user
) {}
