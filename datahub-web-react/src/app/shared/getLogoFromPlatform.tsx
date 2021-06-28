import lookerLogo from '../../images/lookerlogo.png';
import supersetLogo from '../../images/supersetlogo.png';
import airflowLogo from '../../images/airflowlogo.png';
import kafkaLogo from '../../images/kafkalogo.png';

/**
 * TODO: This is a temporary solution, until the backend can push logos for all data platform types.
 */
export function getLogoFromPlatform(platform: string) {
    if (platform.toLowerCase() === 'looker') {
        return lookerLogo;
    }
    if (platform.toLowerCase() === 'superset') {
        return supersetLogo;
    }
    if (platform.toLowerCase() === 'airflow') {
        return airflowLogo;
    }
    return undefined;
}
