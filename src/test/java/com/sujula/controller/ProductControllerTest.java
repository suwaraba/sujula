package com.sujula.controller;

import com.sujula.dto.response.PagedResponse;
import com.sujula.dto.response.product.ProductCardResponse;
import com.sujula.exceptions.BadRequestException;
import com.sujula.model.user.User;
import com.sujula.service.ProductService;
import com.sujula.service.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Query-parameter handling on the catalogue endpoints, and who the writes act as. */
class ProductControllerTest {

    private ProductService productService;
    private ProductController controller;

    @BeforeEach
    void setUp() {
        productService = mock(ProductService.class);
        controller = new ProductController(productService, mock(StorageService.class));
    }

    private Authentication authenticationFor(long userId) {
        User user = new User();
        user.setId(userId);
        return new UsernamePasswordAuthenticationToken(user, null, List.of());
    }

    private void stubSearch() {
        Page<ProductCardResponse> empty = new PageImpl<>(List.of());
        when(productService.searchNearUser(any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(empty);
    }

    @Test
    void searchWithoutCoordinatesIsAllowed() {
        stubSearch();

        ResponseEntity<PagedResponse<ProductCardResponse>> response =
                controller.search("kettle", null, null, null, null, 0, 20);

        assertEquals(200, response.getStatusCode().value());
        // A shopper who has not shared their location still gets results —
        // ranking simply falls back to promotion and score.
        verify(productService).searchNearUser(eq("kettle"), isNull(), isNull(), isNull(), isNull(),
                any(Pageable.class));
    }

    @Test
    void blankSearchTermBecomesNull() {
        stubSearch();

        controller.search("   ", null, null, null, null, 0, 20);

        verify(productService).searchNearUser(isNull(), isNull(), isNull(), isNull(), isNull(),
                any(Pageable.class));
    }

    @Test
    void impossibleCoordinatesAreRejected() {
        // Left through, MySQL clamps them inside the distance expression and
        // ranks the whole catalogue as if the shopper were somewhere else.
        BadRequestException error = assertThrows(BadRequestException.class,
                () -> controller.search("kettle", null, null, 900.0, 0.0, 0, 20));
        assertTrue(error.getMessage().contains("userLat"));

        assertThrows(BadRequestException.class,
                () -> controller.search("kettle", null, null, 0.0, -181.0, 0, 20));
    }

    @Test
    void pageBoundsAreEnforced() {
        assertThrows(BadRequestException.class,
                () -> controller.search("kettle", null, null, null, null, 0, 5000));
        assertThrows(BadRequestException.class,
                () -> controller.search("kettle", null, null, null, null, 0, 0));
        assertThrows(BadRequestException.class,
                () -> controller.search("kettle", null, null, null, null, -1, 20));
    }

    @Test
    void browsePagesCarryNoSort() {
        stubSearch();

        controller.search("kettle", null, null, null, null, 1, 10);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(productService).searchNearUser(any(), any(), any(), any(), any(), pageable.capture());
        // The browse queries are native and carry their own ranking; a Pageable
        // sort would be appended to that and break the SQL.
        assertTrue(pageable.getValue().getSort().isUnsorted());
        assertEquals(1, pageable.getValue().getPageNumber());
        assertEquals(10, pageable.getValue().getPageSize());
    }

    @Test
    void vendorWritesActAsThePrincipalNotTheUrl() {
        controller.delete(authenticationFor(4L), 7L);

        // The vendor id is never taken from the request, so there is no path to
        // another seller's catalogue.
        verify(productService).delete(7L, 4L);
    }

    @Test
    void unauthenticatedVendorCallIsDenied() {
        assertThrows(AccessDeniedException.class, () -> controller.myProducts(null, 0, 20));
    }
}
