package com.sujula.controller;

import com.sujula.dto.request.user.AddressRequest;
import com.sujula.dto.response.user.AddressResponse;
import com.sujula.model.user.User;
import com.sujula.service.AddressService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The signed-in buyer's own address book.
 *
 * <p>There is no path to another buyer's addresses: every id comes from the
 * authenticated principal, never from the URL.
 */
@RestController
@RequestMapping("/api/user/addresses")
@PreAuthorize("isAuthenticated()")
public class AddressController {

    private final AddressService addressService;

    public AddressController(AddressService addressService) {
        this.addressService = addressService;
    }

    @GetMapping
    public ResponseEntity<List<AddressResponse>> myAddresses(Authentication authentication) {
        return ResponseEntity.ok(addressService.findMine(currentUserId(authentication)));
    }

    /** What checkout preselects. 204 when the buyer has saved none yet. */
    @GetMapping("/default")
    public ResponseEntity<AddressResponse> defaultAddress(Authentication authentication) {
        AddressResponse address = addressService.findDefault(currentUserId(authentication));
        return address != null ? ResponseEntity.ok(address) : ResponseEntity.noContent().build();
    }

    @GetMapping("/{addressId}")
    public ResponseEntity<AddressResponse> one(Authentication authentication, @PathVariable Long addressId) {
        return ResponseEntity.ok(addressService.findOne(addressId, currentUserId(authentication)));
    }

    @PostMapping
    public ResponseEntity<AddressResponse> create(Authentication authentication,
                                                  @Valid @RequestBody AddressRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(addressService.create(currentUserId(authentication), request));
    }

    @PutMapping("/{addressId}")
    public ResponseEntity<AddressResponse> update(Authentication authentication,
                                                  @PathVariable Long addressId,
                                                  @Valid @RequestBody AddressRequest request) {
        return ResponseEntity.ok(addressService.update(addressId, currentUserId(authentication), request));
    }

    @PatchMapping("/{addressId}/default")
    public ResponseEntity<AddressResponse> makeDefault(Authentication authentication,
                                                       @PathVariable Long addressId) {
        return ResponseEntity.ok(addressService.makeDefault(addressId, currentUserId(authentication)));
    }

    @DeleteMapping("/{addressId}")
    public ResponseEntity<Void> delete(Authentication authentication, @PathVariable Long addressId) {
        addressService.delete(addressId, currentUserId(authentication));
        return ResponseEntity.noContent().build();
    }

    private Long currentUserId(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof User user && user.getId() != null) {
            return user.getId();
        }
        throw new AccessDeniedException("Authentication is required");
    }
}
