package io.github.kanabig.curtaincall.user.config;

import io.github.kanabig.curtaincall.common.web.error.BusinessException;
import io.github.kanabig.curtaincall.user.error.UserErrorCode;
import io.github.kanabig.curtaincall.user.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** [USR-4] 환경변수로 받은 계정을 ADMIN으로 시드한다. 값이 없으면 아무것도 하지 않는다. */
@Component
public class AdminSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminSeedRunner.class);

    private final UserProperties properties;
    private final UserService userService;

    public AdminSeedRunner(UserProperties properties, UserService userService) {
        this.properties = properties;
        this.userService = userService;
    }

    @Override
    public void run(ApplicationArguments args) {
        UserProperties.Admin admin = properties.admin();
        if (admin == null || !admin.configured()) {
            log.info("ADMIN 시드 계정이 설정되지 않아 건너뛴다");
            return;
        }
        try {
            userService.seedAdmin(admin.email(), admin.password());
        } catch (BusinessException e) {
            // 인스턴스 여러 개가 동시에 시드하면 하나만 만들어진다
            if (e.errorCode() != UserErrorCode.EMAIL_DUPLICATED) {
                throw e;
            }
        }
        log.info("ADMIN 시드 계정을 확인했다");
    }
}
