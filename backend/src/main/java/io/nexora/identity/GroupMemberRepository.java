package io.nexora.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface GroupMemberRepository extends JpaRepository<GroupMember, GroupMember.Key> {
    List<GroupMember> findByMemberIdIn(Collection<UUID> memberIds);
    List<GroupMember> findByGroupId(UUID groupId);
    boolean existsByGroupIdAndMemberId(UUID groupId, UUID memberId);
}
