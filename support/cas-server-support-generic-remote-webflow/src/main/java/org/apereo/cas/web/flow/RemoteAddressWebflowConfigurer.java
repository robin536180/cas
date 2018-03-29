package org.apereo.cas.web.flow;

import lombok.extern.slf4j.Slf4j;
import org.apereo.cas.configuration.CasConfigurationProperties;
import org.apereo.cas.web.flow.configurer.AbstractCasWebflowConfigurer;
import org.springframework.context.ApplicationContext;
import org.springframework.webflow.definition.registry.FlowDefinitionRegistry;
import org.springframework.webflow.engine.ActionState;
import org.springframework.webflow.engine.Flow;
import org.springframework.webflow.engine.builder.support.FlowBuilderServices;

/**
 * The {@link RemoteAddressWebflowConfigurer} is responsible for
 * adjusting the CAS webflow context for remote address integration.
 *
 * @author Misagh Moayyed
 * @since 4.2
 */
@Slf4j
public class RemoteAddressWebflowConfigurer extends AbstractCasWebflowConfigurer {

    public RemoteAddressWebflowConfigurer(final FlowBuilderServices flowBuilderServices, 
                                          final FlowDefinitionRegistry loginFlowDefinitionRegistry,
                                          final ApplicationContext applicationContext,
                                          final CasConfigurationProperties casProperties) {
        super(flowBuilderServices, loginFlowDefinitionRegistry, applicationContext, casProperties);
    }

    @Override
    protected void doInitialize() {
        final var flow = getLoginFlow();
        if (flow != null) {
            final var actionState = createActionState(flow, "startAuthenticate", createEvaluateAction("remoteAddressCheck"));
            actionState.getTransitionSet().add(createTransition(CasWebflowConstants.TRANSITION_ID_SUCCESS,
                    CasWebflowConstants.STATE_ID_SEND_TICKET_GRANTING_TICKET));
            actionState.getTransitionSet().add(createTransition(CasWebflowConstants.TRANSITION_ID_ERROR, getStartState(flow).getId()));
            actionState.getExitActionList().add(createEvaluateAction(CasWebflowConstants.ACTION_ID_CLEAR_WEBFLOW_CREDENTIALS));
            registerMultifactorProvidersStateTransitionsIntoWebflow(actionState);
        }
    }
}
