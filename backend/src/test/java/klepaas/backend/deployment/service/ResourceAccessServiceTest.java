package klepaas.backend.deployment.service;

import klepaas.backend.deployment.entity.CloudVendor;
import klepaas.backend.deployment.entity.Deployment;
import klepaas.backend.deployment.entity.SourceRepository;
import klepaas.backend.deployment.repository.DeploymentRepository;
import klepaas.backend.deployment.repository.SourceRepositoryRepository;
import klepaas.backend.global.exception.EntityNotFoundException;
import klepaas.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ResourceAccessServiceTest {
    @Mock SourceRepositoryRepository repositories;
    @Mock DeploymentRepository deployments;
    @InjectMocks ResourceAccessService access;

    @Test
    void repositoryIsVisibleOnlyToItsOwner() {
        User owner = User.builder().email("a@example.com").name("A").providerId("a").build();
        ReflectionTestUtils.setField(owner, "id", 1L);
        SourceRepository repository = SourceRepository.builder().user(owner).owner("org")
                .repoName("app").gitUrl("https://github.com/org/app").cloudVendor(CloudVendor.NCP).build();
        given(repositories.findById(9L)).willReturn(Optional.of(repository));

        assertThat(access.requireRepository(9L, 1L)).isSameAs(repository);
        assertThatThrownBy(() -> access.requireRepository(9L, 2L)).isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> access.requireRepository(9L, null)).isInstanceOf(EntityNotFoundException.class);
        verify(repositories, org.mockito.Mockito.times(2)).findById(9L);
    }

    @Test
    void deploymentIsVisibleOnlyToItsOwner() {
        User owner = User.builder().email("a@example.com").name("A").providerId("a").build();
        ReflectionTestUtils.setField(owner, "id", 1L);
        SourceRepository repository = SourceRepository.builder().user(owner).owner("org")
                .repoName("app").gitUrl("https://github.com/org/app").cloudVendor(CloudVendor.NCP).build();
        Deployment deployment = Deployment.builder().sourceRepository(repository).branchName("main").commitHash("abc").build();
        given(deployments.findById(4L)).willReturn(Optional.of(deployment));

        assertThat(access.requireDeployment(4L, 1L)).isSameAs(deployment);
        assertThatThrownBy(() -> access.requireDeployment(4L, 2L)).isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> access.requireDeployment(4L, null)).isInstanceOf(EntityNotFoundException.class);
        verify(deployments, org.mockito.Mockito.times(2)).findById(4L);
        verify(repositories, never()).findById(4L);
    }
}
