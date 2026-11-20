package com.nfcguard.server.auth;

import com.nfcguard.server.model.Device;
import com.nfcguard.server.repository.AnomalyEventRepository;
import com.nfcguard.server.repository.DeviceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * 기기 등록/인증. 인증 없이 받으면 누구나 가짜 이벤트·가짜 라벨을 넣어
 * 파인튜닝 데이터를 오염(poisoning)시킬 수 있으므로 모든 이벤트 API에 적용한다.
 */
@Service
public class DeviceAuthService {

    private final DeviceRepository repository;
    private final AnomalyEventRepository eventRepository;
    private final SecureRandom random = new SecureRandom();

    public DeviceAuthService(DeviceRepository repository, AnomalyEventRepository eventRepository) {
        this.repository = repository;
        this.eventRepository = eventRepository;
    }

    public record Registration(String deviceToken, String apiKey) {}

    public Registration register() {
        String deviceToken = UUID.randomUUID().toString();
        byte[] keyBytes = new byte[32];
        random.nextBytes(keyBytes);
        String apiKey = Base64.getUrlEncoder().withoutPadding().encodeToString(keyBytes);
        repository.save(new Device(deviceToken, sha256(apiKey), Instant.now()));
        return new Registration(deviceToken, apiKey);
    }

    /** 사용자 데이터 삭제 요청: 이 기기의 이벤트와 등록 정보를 모두 지운다. @return 삭제한 이벤트 수 */
    @Transactional
    public int deleteDeviceAndData(String deviceToken) {
        int events = eventRepository.deleteAllByDeviceToken(deviceToken);
        repository.findByDeviceToken(deviceToken).ifPresent(repository::delete);
        return events;
    }

    /** 관리자: 통제 실험 수집용 테스트 휴대폰 지정/해제. @return 기기가 없으면 false */
    @Transactional
    public boolean setTestDevice(String deviceToken, boolean testDevice) {
        return repository.findByDeviceToken(deviceToken).map(d -> {
            d.setTestDevice(testDevice);
            repository.save(d);
            return true;
        }).orElse(false);
    }

    /** 토큰/키가 맞으면 해당 기기 토큰 반환 */
    public Optional<String> authenticate(String deviceToken, String apiKey) {
        if (deviceToken == null || apiKey == null) return Optional.empty();
        return repository.findByDeviceToken(deviceToken)
                .filter(d -> constantTimeEquals(d.getApiKeyHash(), sha256(apiKey)))
                .map(Device::getDeviceToken);
    }

    static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
