package io.github.kanabig.curtaincall.queue.service;

import io.github.kanabig.curtaincall.queue.repository.QueueRepository;
import io.github.kanabig.curtaincall.queue.repository.SessionConf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 회차 이벤트를 Redis conf에 반영한다. 같은 이벤트를 다시 적용해도 결과가 같고, 오래된 이벤트는 버린다. */
@Service
public class SessionConfService {

    private static final Logger log = LoggerFactory.getLogger(SessionConfService.class);

    private final QueueRepository repository;

    public SessionConfService(QueueRepository repository) {
        this.repository = repository;
    }

    public void apply(SessionConf conf) {
        boolean applied = repository.applyConf(conf);
        if (!applied) {
            log.info("이미 반영한 것보다 오래된 회차 이벤트라 무시한다: sessionId={} type={}", conf.sessionId(), conf.type());
        }
    }
}
