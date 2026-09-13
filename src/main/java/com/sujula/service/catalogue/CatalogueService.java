package com.sujula.service.catalogue;

import com.sujula.dto.request.catalogue.ProductFilter;
import com.sujula.dto.response.catalogue.CatalogueResponses;
import org.springframework.data.domain.Pageable;

/**
 * The public catalogue: what a shopper can see without an account.
 *
 * <p>Every read takes a {@link BrowsingContext}, and that is the C1 contract made
 * into a signature. The context carries where the goods are going and what the
 * prices should say as two separate fields resolved from two separate sources,
 * so nothing in here can accidentally rank a catalogue against the person paying
 * or quote them in the recipient's currency.
 *
 * <p>DTOs out. No entity crosses this boundary — a {@code Product} carries its
 * vendor, and a vendor carries a user, and serialising one would take a person's
 * account out through a public endpoint.
 */
public interface CatalogueService {

    // ── Categories ───────────────────────────────────────────────────────────

    /** The whole tree, with product counts. Cacheable: it changes rarely. */
    CatalogueResponses.CategoryTree categoryTree();

    /** One category with the filter schema derived from what is actually in it. */
    CatalogueResponses.CategoryDetail categoryBySlug(String slug, BrowsingContext context);

    // ── Products ─────────────────────────────────────────────────────────────

    /** The filtered, delivery-ranked browse behind GET /products and GET /search. */
    CatalogueResponses.ProductPage browse(ProductFilter filter, BrowsingContext context,
                                          Pageable pageable);

    /**
     * One product, priced in the buyer's currency, with serviceability when a
     * destination was given.
     */
    CatalogueResponses.ProductDetail productBySlug(String slug, BrowsingContext context);

    CatalogueResponses.ProductDetail productById(Long productId, BrowsingContext context);

    /** This product's variants, priced in the buyer's currency. */
    java.util.List<CatalogueResponses.Variant> variants(Long productId, BrowsingContext context);

    /** Products a shopper looking at this one would also consider. */
    CatalogueResponses.ProductPage related(Long productId, BrowsingContext context,
                                           Pageable pageable);

    // ── Reviews ──────────────────────────────────────────────────────────────

    CatalogueResponses.ReviewPage reviews(Long productId, Pageable pageable);

    // ── Stores and brands ────────────────────────────────────────────────────

    CatalogueResponses.Store storeBySlug(String slug);

    CatalogueResponses.ProductPage storeProducts(String slug, ProductFilter filter,
                                                 BrowsingContext context, Pageable pageable);

    CatalogueResponses.Brands brands();

    // ── Search ───────────────────────────────────────────────────────────────

    /** Typeahead. Names only, and bounded — a suggestion list nobody scrolls. */
    CatalogueResponses.Suggestions suggest(String query, BrowsingContext context, int limit);
}
