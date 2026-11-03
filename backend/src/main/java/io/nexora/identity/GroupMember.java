package io.nexora.identity;

import jakarta.persistence.*;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "group_member")
@IdClass(GroupMember.Key.class)
public class GroupMember {
    @Id
    private UUID groupId;
    @Id
    private UUID memberId;

    protected GroupMember() {}

    public GroupMember(UUID groupId, UUID memberId) {
        this.groupId = groupId;
        this.memberId = memberId;
    }

    public UUID getGroupId() { return groupId; }
    public UUID getMemberId() { return memberId; }

    public static class Key implements Serializable {
        private UUID groupId;
        private UUID memberId;

        public Key() {}

        public Key(UUID groupId, UUID memberId) {
            this.groupId = groupId;
            this.memberId = memberId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(groupId, k.groupId) && Objects.equals(memberId, k.memberId);
        }

        @Override
        public int hashCode() { return Objects.hash(groupId, memberId); }
    }
}
