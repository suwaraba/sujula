package com.sujula.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Describes the API for Swagger UI and for any client generated from it.
 *
 * <p>The two things a caller gets wrong on this API, spelled out in the
 * description because neither is discoverable from the endpoint list: writes
 * need a CSRF token, and a guest can build a basket before the payer signs in.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI sujulaOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Sujula Marketplace API")
                        .version("0.0.1-SNAPSHOT")
                        .description("""
                                Multivendor marketplace API. Gambia-first: vendors list in their own \
                                currency, buyers see and pay in theirs, and delivery is priced per \
                                product from distance and weight.

                                **Authentication** is a server session. `POST /api/users/login` sets \
                                `JSESSIONID`; send it on subsequent calls. Roles are CUSTOMER, VENDOR, \
                                ADMIN, DELIVERY and PICKUP_OPERATOR.

                                **CSRF applies to every write.** Make any request first to receive the \
                                `XSRF-TOKEN` cookie, then send that same value back in the \
                                `X-XSRF-TOKEN` header on POST, PUT, PATCH and DELETE. Swagger UI does \
                                this for you. Without it every write returns 403 with an empty body.

                                **New guest-payer checkout is retired.** `POST /api/guest/orders` returns \
                                410 Gone; the payer must sign in and use `POST /checkout`. Historic guest \
                                order lookup, cancellation and payment endpoints remain temporarily preserved \
                                for existing records under their legacy access model.

                                **Currency isolation.** Nothing under `/api/vendor/**` returns a buyer's \
                                currency, location or total. A vendor sees identifiers and their own \
                                payout, in their own settlement currency.
                                """)
                        .contact(new Contact().name("Sujula")))
                .components(new Components()
                        .addSecuritySchemes("session", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE)
                                .name("JSESSIONID")
                                .description("Session cookie issued by POST /api/users/login."))
                        .addSecuritySchemes("csrf", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-XSRF-TOKEN")
                                .description("Echo the XSRF-TOKEN cookie's value. Required on all writes.")));
    }
}
