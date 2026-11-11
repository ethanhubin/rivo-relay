"""DSH acceptance evidence and compatibility with the prototype's receipts."""
from ..receipts import InputReceipt, ReceiptConflict, input_accepted


def decode_legacy(receipt, submission_id):
    if 'runtime' not in receipt and 'acceptance' not in receipt:
        return None
    if not (receipt.get('runtime') == 'dsh' and receipt.get('acceptance') == 'input'
            and receipt.get('threadId') and receipt.get('requestId')):
        raise ValueError('Invalid legacy DSH input receipt')
    if receipt['requestId'] != submission_id:
        raise ReceiptConflict('DSH input receipt belongs to another submission')
    if receipt.get('turnId') or receipt.get('turn_id'):
        raise ValueError('DSH input acceptance cannot assert a turn')
    return InputReceipt('input-accepted', submission_id, receipt['threadId'])


def accepted_receipt(result, thread_id, request_id):
    if result.get('accepted') is not True or result.get('requestId') != request_id:
        return None
    return input_accepted(request_id, thread_id, runtime='dsh', acceptance='input', requestId=request_id)
