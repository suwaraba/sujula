package com.sujula.dto.request.user;

import lombok.Data;

@Data
public class BlockUserRequest {

    private boolean blocked;
    private boolean fraud;
}
