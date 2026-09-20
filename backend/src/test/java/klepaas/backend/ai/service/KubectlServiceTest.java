package klepaas.backend.ai.service;

import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodListBuilder;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServiceListBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import klepaas.backend.deployment.entity.SourceRepository;
import klepaas.backend.deployment.repository.SourceRepositoryRepository;
import klepaas.backend.global.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KubectlServiceTest {
    @SuppressWarnings("unchecked")
    private <T> T scopedMock(Class<?> type) {
        return (T) Mockito.mock(type);
    }

    @Test
    void wrongNamespaceIsRejectedBeforeKubernetesCall() {
        var client = Mockito.mock(KubernetesClient.class);
        var service = new KubectlService(client, Mockito.mock(SourceRepositoryRepository.class));
        ReflectionTestUtils.setField(service, "defaultNamespace", "klepaas");

        assertThatThrownBy(() -> service.listPods("kube-system", 1L)).isInstanceOf(BusinessException.class);
        Mockito.verifyNoInteractions(client);
    }

    @Test
    void ownPodListIsSelectedByRepositoryLabelAndForeignPodNameCannotReadLogs() {
        var client = Mockito.mock(KubernetesClient.class, Mockito.RETURNS_DEEP_STUBS);
        var repositories = Mockito.mock(SourceRepositoryRepository.class);
        var repository = SourceRepository.builder().owner("owner").repoName("repo").build();
        ReflectionTestUtils.setField(repository, "id", 7L);
        Mockito.when(repositories.findAllByUserId(1L)).thenReturn(List.of(repository));
        Mockito.when(client.pods().inNamespace("klepaas"))
                .thenReturn(scopedMock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class));
        Mockito.when(client.pods().inNamespace("klepaas").withLabel("klepaas.io/repository-id", "7"))
                .thenReturn(scopedMock(io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable.class));
        Mockito.when(client.pods().inNamespace("klepaas").withLabel("klepaas.io/repository-id", "7").list())
                .thenReturn(new PodListBuilder().withItems(new PodBuilder().withNewMetadata()
                        .withName("owned-pod").addToLabels("klepaas.io/repository-id", "7")
                        .addToLabels("app.kubernetes.io/name", "owned-app").endMetadata()
                        .withNewSpec().endSpec().withNewStatus().withPhase("Running").endStatus().build()).build());
        var service = new KubectlService(client, repositories);
        ReflectionTestUtils.setField(service, "defaultNamespace", "klepaas");

        assertThat(service.listPods(null, 1L).data().formatted().toString()).contains("owned-pod");
        assertThat(service.getPodLogs("foreign-pod", null, 100, 1L).type()).isEqualTo("error");
        Mockito.verify(client.pods().inNamespace("klepaas"), Mockito.never()).withName("foreign-pod");
    }

    @Test
    void ownedServiceRetainsPortDetailAndForeignServiceIsHidden() {
        var client = Mockito.mock(KubernetesClient.class, Mockito.RETURNS_DEEP_STUBS);
        var repositories = Mockito.mock(SourceRepositoryRepository.class);
        var repository = SourceRepository.builder().owner("owner").repoName("repo").build();
        ReflectionTestUtils.setField(repository, "id", 7L);
        Mockito.when(repositories.findAllByUserId(1L)).thenReturn(List.of(repository));
        Mockito.when(client.services().inNamespace("klepaas"))
                .thenReturn(scopedMock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class));
        Mockito.when(client.services().inNamespace("klepaas").withLabel("klepaas.io/repository-id", "7"))
                .thenReturn(scopedMock(io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable.class));
        Mockito.when(client.services().inNamespace("klepaas").withLabel("klepaas.io/repository-id", "7").list())
                .thenReturn(new ServiceListBuilder().withItems(new ServiceBuilder().withNewMetadata()
                        .withName("own-service").withNamespace("klepaas").endMetadata().withNewSpec()
                        .withType("ClusterIP").withClusterIP("10.0.0.7")
                        .addNewPort().withPort(80).withProtocol("TCP").endPort().endSpec().build()).build());
        var service = new KubectlService(client, repositories);
        ReflectionTestUtils.setField(service, "defaultNamespace", "klepaas");

        assertThat(service.getService("own-service", null, 1L).data().formatted().toString())
                .contains("80/TCP");
        assertThat(service.getService("foreign-service", null, 1L).type()).isEqualTo("error");
        Mockito.verify(client.services().inNamespace("klepaas"), Mockito.never()).withName("foreign-service");
    }
}
