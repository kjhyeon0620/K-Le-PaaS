package klepaas.backend.global.service;

import klepaas.backend.deployment.entity.Deployment;

public interface NotificationService {

    void notifyDeploymentStarted(Deployment deployment);

    void notifyDeploymentSuccess(Deployment deployment);

    void notifyDeploymentFailed(Deployment deployment, String reason);
}
