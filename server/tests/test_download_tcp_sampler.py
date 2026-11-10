import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('sampler', Path(__file__).resolve().parents[2] / 'scripts/sample-download-tcp.py')
sampler = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sampler)


class SamplerTests(unittest.TestCase):
    def test_frontend_metrics_preserve_cumulative_and_inflight_distinction(self):
        rows = sampler.parse_ss('''ESTAB 0 1400 10.1.0.7:8443 192.0.2.1:1234
 bbr rtt:180.5/20.1 rto:381 cwnd:18 bytes_sent:5000 bytes_acked:2000 bytes_retrans:3000 busy:1200ms rwnd_limited:950ms(79%) retrans:2/8 snd_wnd:32768
ESTAB 0 0 [::1]:8443 [::1]:2345
 cubic rtt:0.1/0.01 cwnd:10 bytes_acked:300
''')
        self.assertEqual(len(rows), 2)
        self.assertEqual(rows[0]['rtt_ms'], 180.5)
        self.assertEqual(rows[0]['total_retrans'], 8)
        self.assertEqual(rows[0]['retrans_inflight'], 2)
        self.assertEqual(rows[0]['rwnd_limited_ms'], 950)
        self.assertEqual(rows[1]['peer'], '[::1]:2345')
        self.assertNotIn('total_retrans', rows[1])

    def test_delta_is_counter_difference_not_tcp_inflight_count(self):
        result = sampler.changed_sample({'bytes_acked': 5000, 'total_retrans': 8},
                                        {'bytes_acked': 2000, 'total_retrans': 3}, 1.2)
        self.assertEqual(result['delta_bytes_acked'], 3000)
        self.assertEqual(result['delta_total_retrans'], 5)
        self.assertEqual(result['sample_interval_ms'], 1200)
        self.assertNotIn('delta_bytes_acked', sampler.changed_sample({'bytes_acked': 1}, {'bytes_acked': 10}, 1))
