package io.nexora.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PrincipalRepository extends JpaRepository<Principal, UUID> {

    @Query("select p from Principal p where p.kind = :kind and lower(p.login) = lower(:login)")
    Optional<Principal> findByKindAndLogin(@Param("kind") Principal.Kind kind, @Param("login") String login);

    @Query("""
            select p from Principal p
            where lower(p.login) like lower(concat('%', :q, '%'))
               or lower(p.displayName) like lower(concat('%', :q, '%'))
            order by p.displayName
            """)
    List<Principal> search(@Param("q") String q);

    List<Principal> findBySiteId(UUID siteId);
}
