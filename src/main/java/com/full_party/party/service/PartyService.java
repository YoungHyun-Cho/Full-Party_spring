package com.full_party.party.service;

import com.full_party.comment.service.CommentService;
import com.full_party.exception.BusinessLogicException;
import com.full_party.exception.ExceptionCode;
import com.full_party.heart.service.HeartService;
import com.full_party.party.entity.Party;
import com.full_party.party.entity.UserParty;
import com.full_party.party.entity.Waiter;
import com.full_party.party.repository.PartyRepository;
import com.full_party.party.repository.UserPartyRepository;
import com.full_party.party.repository.WaiterRepository;
import com.full_party.user.entity.User;
import com.full_party.user.service.UserService;
import com.full_party.values.PartyState;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class PartyService {
    private final PartyRepository partyRepository;
    private final UserPartyRepository userPartyRepository;
    private final WaiterRepository waiterRepository;
    private final UserService userService;
    private final HeartService heartService;
    private final CommentService commentService;

    public PartyService(PartyRepository partyRepository, UserPartyRepository userPartyRepository, WaiterRepository waiterRepository, UserService userService, HeartService heartService, CommentService commentService) {
        this.partyRepository = partyRepository;
        this.userPartyRepository = userPartyRepository;
        this.waiterRepository = waiterRepository;
        this.userService = userService;
        this.heartService = heartService;
        this.commentService = commentService;
    }

    public Party createParty(Party party, User user) {
        party.setUser(user);
        party.setPartyState(PartyState.RECRUITING);
        return partyRepository.save(party);
    }

    // 역할에 따른 조회 - 파티장인 파티 검색
    private List<Party> findLeadingParty(Long userId) {
        return partyRepository.findByUserId(userId);
    }

    // 역할에 따른 조회 - 파티원인 파티 검색
    private List<Party> findParticipatingParty(Long userId) {

        return userPartyRepository.findByUserId(userId).stream()
                .map(userParty -> findParty(userParty.getParty().getId()))
                .collect(Collectors.toList());
    }

    private List<Party> filterProgressingParty(List<Party> parties) {

        return parties.stream()
                .filter(party -> party.getPartyState() == PartyState.RECRUITING || party.getPartyState() == PartyState.FULL_PARTY)
                .collect(Collectors.toList());
    }

    private List<Party> filterCompletedParty(List<Party> parties) {

        return parties.stream()
                .filter(party -> party.getPartyState() == PartyState.COMPLETED)
                .collect(Collectors.toList());
    }

    public List<Party> findProgressingLeadingParty(Long userId) {
        return filterProgressingParty(findLeadingParty(userId));
    }

    public List<Party> findProgressingParticipatingParty(Long userId) {
        return filterProgressingParty(findParticipatingParty(userId));
    }

    public List<Party> findProgressingMyParty(Long userId) {

        return filterProgressingParty(
                Stream
                    .concat(findLeadingParty(userId).stream(), findParticipatingParty(userId).stream())
                    .collect(Collectors.toList())
        );
    }

    public List<Party> findCompletedMyParty(Long userId) {

        return filterCompletedParty(
                Stream
                    .concat(findLeadingParty(userId).stream(), findParticipatingParty(userId).stream())
                    .collect(Collectors.toList())
        );
    }

    public List<Party> findLocalParties(Long userId, String region) {

        List<Party> localParties = partyRepository.findByRegion(region);

        setIsHeart(userId, localParties);
        setMembers(localParties);

        return localParties;
    }

    private void setIsHeart(Long userId, List<Party> partyList) {

        partyList.stream()
                .forEach(party -> party.setIsHeart(heartService.checkIsHeart(userId, party.getId())));
    }

    private void setMembers(List<Party> partyList) {
        // getPartyMembers => partyId를 받아 파티장을 포함한 파티 전체 멤버를 리스트로 리턴

        partyList.stream()
                .forEach(party -> party.setMemberList(findPartyMembers(party, true)));
    }

    public List<Party.PartyMember> findPartyMembers(Party party, Boolean includeLeader) {

        List<Party.PartyMember> members = userPartyRepository.findByPartyId(party.getId()).stream()
                .map(userParty -> {
                    Party.PartyMember partyMember = new Party.PartyMember(userService.findUser(userParty.getUser().getId()));
                    partyMember.setJoinDate(findUserParty(partyMember.getId(), party.getId()).getCreatedAt());
                    partyMember.setMessage(userParty.getMessage());
                    return partyMember;
                })
                .collect(Collectors.toList());

        if (includeLeader) {
            Party.PartyMember leader = new Party.PartyMember(party.getUser());
            leader.setJoinDate(party.getCreatedAt());
            members.add(0, leader);
        }

        return members;
    }

    public Party findParty(Long partyId) {

        Party foundParty = findVerifiedParty(partyId);

        return setTransientValues(foundParty);
    }

    public Party findParty(Long userId, Long partyId) {

        Party foundParty = findVerifiedParty(partyId);

        return setTransientValues(foundParty, userId);
    }


    private Party setTransientValues(Party party) {

        party.setMemberList(findPartyMembers(party, true));
        party.setWaiterList(findWaiters(party));
        party.setHeartCount(heartService.findHearts(party).size());

        return party;
    }

    private Party setTransientValues(Party party, Long userId) {

        setTransientValues(party);

        if (heartService.findHeart(userId, party.getId()) != null) party.setIsHeart(true);
        else party.setIsHeart(false);

        party.setIsReviewed(checkIsReviewed(userService.findUser(userId), party));

        return party;
    }

    public List<Party> findPartiesByTag(String tagValue, Long userId, String region) {

        return partyRepository.searchPartiesByTagValue(tagValue, region).stream()
                .map(party -> setTransientValues(party, userId))
                .collect(Collectors.toList());
    }

    public List<Party> findPartiesByKeyword(String keyword, Long userId, String region) {

        return partyRepository.searchPartiesByKeyword(keyword, region).stream()
                .map(party -> setTransientValues(party, userId))
                .collect(Collectors.toList());
    }

    public Party updateParty(Party party) {

        Party foundParty = findVerifiedParty(party.getId());

        Party updatedParty = new Party(foundParty, party);

        return partyRepository.save(updatedParty);
    }

    public Party updatePartyState(Long partyId, PartyState partyState) {

        Party foundParty = findParty(partyId);

        foundParty.setPartyState(partyState);

        return partyRepository.save(foundParty);
    }

    // 필요 예시 : 알림 창에서 파티 접근 -> 파티 삭제됨 -> 404 응답
    private Party findVerifiedParty(Long partyId) {
        Optional<Party> optionalParty = partyRepository.findById(partyId);
        Party party = optionalParty.orElseThrow(() -> new BusinessLogicException(ExceptionCode.PARTY_NOT_FOUND));
        return party;
    }

    public Waiter createWaiter(Long userId, Long partyId, String message) {

        User user = userService.findUser(userId);
        Party party = findParty(partyId);
        Waiter waiter = new Waiter(user, party, message);

        return waiterRepository.save(waiter);
    }

    public void updateMessage(Long userId, Long partyId, String message) {
        try {
            Waiter foundWaiter = findWaiter(userId, partyId);
            foundWaiter.setMessage(message);
            waiterRepository.save(foundWaiter);
        }
        catch (BusinessLogicException e) {
            UserParty foundUserParty = findUserParty(userId, partyId);
            foundUserParty.setMessage(message);
            userPartyRepository.save(foundUserParty);
        }
    }

    private Waiter findWaiter(Long userId, Long partyId) {
        return findVerifiedWaiter(userId, partyId);
    }

    private List<Party.PartyMember> findWaiters(Party party) {

        return waiterRepository.findByPartyId(party.getId()).stream()
                .map(waiter -> {
                    Party.PartyMember partyMember = new Party.PartyMember(userService.findUser(waiter.getUser().getId()));
                    partyMember.setMessage(waiter.getMessage());
                    return partyMember;
                })
                .collect(Collectors.toList());
    }

    private Waiter findVerifiedWaiter(Long userId, Long partyId) {
        Optional<Waiter> optionalWaiter = waiterRepository.findByUserIdAndPartyId(userId, partyId);
        Waiter waiter = optionalWaiter.orElseThrow(() -> new BusinessLogicException(ExceptionCode.WAITER_NOT_FOUND));
        return waiter;
    }

    public UserParty createUserParty(Long userId, Long partyId) {

        Waiter foundWaiter = findWaiter(userId, partyId);

        UserParty userParty = new UserParty(
                foundWaiter.getUser(),
                foundWaiter.getParty(),
                foundWaiter.getMessage(),
                false
        );

        deleteWaiter(foundWaiter);

        return userPartyRepository.save(userParty);
    }

    public void deleteWaiter(Long userId, Long partyId) {
        Waiter foundWaiter = findWaiter(userId, partyId);
        deleteWaiter(foundWaiter);
    }

    public void deleteWaiter(Waiter waiter) {
        waiterRepository.delete(waiter);
    }

    private UserParty findUserParty(Long userId, Long partyId) {
        return findVerifiedUserParty(userId, partyId);
    }

    private UserParty findVerifiedUserParty(Long userId, Long partyId) {
        Optional<UserParty> optionalUserParty = userPartyRepository.findByUserIdAndPartyId(userId, partyId);
        UserParty userParty = optionalUserParty.orElseThrow(() -> new BusinessLogicException(ExceptionCode.USER_PARTY_NOT_FOUND));
        return userParty;
    }

    public void deleteUserParty(Long userId, Long partyId) {
        UserParty foundUserParty = findUserParty(userId, partyId);
        deleteUserParty(foundUserParty);
    }

    public void deleteUserParty(UserParty userParty) {
        userPartyRepository.delete(userParty);
    }

    public void deleteParty(Party party) {
        party.setPartyState(PartyState.DISMISSED);
        partyRepository.save(party);
    }

    public void changeIsReviewed(User user, Party party, Integer resultsLength) {

        // 파티장은 userParty X -> 파티원만 체크
        if (party.getUser().getId() != user.getId()) {

            // 자신을 제외한 파티원을 모두 리뷰했는지 체크
            if (userPartyRepository.findByPartyId(party.getId()).size() == resultsLength) {
                UserParty foundUserParty = findUserParty(user.getId(), party.getId());
                foundUserParty.setIsReviewed(true);
                userPartyRepository.save(foundUserParty);
            }
        }
    }

    public Boolean checkIsReviewed(User user, Party party) {

        if (party.getPartyState() == PartyState.COMPLETED && party.getUser().getId() == user.getId()) return true;

        try {
            return findUserParty(user.getId(), party.getId()).getIsReviewed();
        }
        catch (BusinessLogicException e) {
            return false;
        }
    }
}
