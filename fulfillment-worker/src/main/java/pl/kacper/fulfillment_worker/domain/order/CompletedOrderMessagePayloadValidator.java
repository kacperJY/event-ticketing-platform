package pl.kacper.fulfillment_worker.domain.order;

import org.springframework.stereotype.Component;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.Validator;
import pl.kacper.exception.InvalidMessageFormatException;
import pl.kacper.fulfillment_worker.domain.message.dto.payload.CompletedOrderMessagePayloadV1;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

@Component
class CompletedOrderMessagePayloadValidator {

    private final Validator validator;

    public CompletedOrderMessagePayloadValidator(Validator validator) {
        this.validator = validator;
    }

    void validate(CompletedOrderMessagePayloadV1 completedOrderMessagePayloadV1) {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(completedOrderMessagePayloadV1, "completedOrderMessagePayloadV1");
        validator.validate(completedOrderMessagePayloadV1, bindingResult);
        List<FieldError> fieldErrors = bindingResult.getFieldErrors();
        if (fieldErrors.isEmpty()) {
            verifyOrderItemIdDuplicates(completedOrderMessagePayloadV1.itemList());
            return;
        }
        StringBuilder stringBuilder = new StringBuilder();

        for (FieldError fieldError : fieldErrors) {
            stringBuilder.append(fieldError.getField()).append(" - ").append(fieldError.getDefaultMessage()).append("\n");
        }
        throw new InvalidMessageFormatException(
                """
                        Invalid message's payload for Order=%s
                        %s
                        """.formatted(completedOrderMessagePayloadV1.orderId(), stringBuilder.toString())
        );
    }

    private void verifyOrderItemIdDuplicates(Collection<CompletedOrderMessagePayloadV1.Item> collection) {
        int originalSize = collection.size();

        HashSet<Long> uniqueOrderItemIds = collection.stream()
                .map(CompletedOrderMessagePayloadV1.Item::orderItemId)
                .collect(Collectors.toCollection(HashSet::new));

        if (uniqueOrderItemIds.size() != originalSize)
            throw new InvalidMessageFormatException("Invalid message payload. Message payload cannot contain duplicated OrderItemId");
    }
}
