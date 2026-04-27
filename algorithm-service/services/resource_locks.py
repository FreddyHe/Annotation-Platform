import threading


# Training and edge inference share the same GPU. Keep one heavyweight YOLO
# operation on the device at a time so edge requests queue instead of racing
# model loading/training and timing out unpredictably.
gpu_lock = threading.Lock()
