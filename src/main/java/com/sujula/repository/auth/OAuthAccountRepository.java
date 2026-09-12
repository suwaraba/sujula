package com.sujula.repository.auth;

import com.sujula.model.auth.OAuthAccount;
import com.sujula.model.constant.AuthProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OAuthAccountRepository extends JpaRepository<OAuthAccount, Long> {

    /** The link, by the provider's immutable subject id — never by email. */
    @Query("SELECT a FROM OAuthAccount a JOIN FETCH a.user "
         + "WHERE a.provider = :provider AND a.providerUserId = :subject")
    Optional<OAuthAccount> findByProviderAndSubject(@Param("provider") AuthProvider provider,
                                                    @Param("subject") String subject);

    List<OAuthAccount> findByUserId(Long userId);

    boolean existsByUserIdAndProvider(Long userId, AuthProvider provider);
}
